import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type StockDetail, type StockItemView, type StockMovement } from "@/lib/api";

/*
 * The real `useAuthedQuery` is used here rather than a stub, and that is the point of the setup.
 * This screen runs two independent queries — the item's own detail, and its movement history — and
 * a single mocked hook answers both with the same object, which makes it impossible to say that a
 * correction re-read the history. Driving the actual hook from mocked `api` methods keeps the two
 * apart and makes `reload()` do the thing the assertions are about.
 */
const {
  authRef,
  movementsRef,
  getItemMock,
  listMovementsMock,
  compensateMock,
  deleteItemMock,
  adjustMock,
  pushMock,
  suggestMock,
} = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: { role: "KITCHEN_MANAGER", userId: "me", fullName: "Radha" },
      getToken: async () => "test-token",
      refresh: () => {},
    },
  },
  movementsRef: { current: [] as StockMovement[] },
  getItemMock: vi.fn(),
  listMovementsMock: vi.fn(),
  compensateMock: vi.fn(),
  deleteItemMock: vi.fn(),
  adjustMock: vi.fn(),
  pushMock: vi.fn(),
  suggestMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useParams: () => ({ id: "item-1" }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => "/inventory/item-1",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      getInventoryItem: getItemMock,
      listMovements: listMovementsMock,
      compensateMovement: compensateMock,
      deleteInventoryItem: deleteItemMock,
      adjustStock: adjustMock,
      getStockValueSuggestion: suggestMock,
    },
  };
});

import InventoryItemPage from "@/app/inventory/[id]/page";

function itemView(overrides: Partial<StockItemView> = {}): StockItemView {
  return {
    itemId: "item-1",
    ingredientId: "ing-1",
    ingredientName: "Toor Dal",
    category: "Pulses",
    storageLocation: "Main store",
    unit: "KG",
    onHand: 8,
    committed: 0,
    available: 8,
    reorderThreshold: 5,
    belowThreshold: false,
    expiringSoon: false,
    soonestExpiry: null,
    notes: null,
    lastCounted: null,
    onOrder: null,
    lastsFor: null,
    ...overrides,
  };
}

function detail(overrides: Partial<StockDetail> = {}): StockDetail {
  return { item: itemView(), batches: [], committed: [], ...overrides };
}

function movement(overrides: Partial<StockMovement> = {}): StockMovement {
  return {
    id: "mv-1",
    ingredientId: "ing-1",
    ingredientName: "Toor Dal",
    storageLocation: "Main store",
    batchId: "b1",
    quantity: -2,
    unit: "KG",
    type: "CONSUMPTION",
    expiryDate: null,
    receivedDate: null,
    reason: null,
    referenceType: null,
    referenceId: null,
    note: null,
    actorUserId: "u1",
    actorName: "Gopal Das",
    createdAt: "2026-09-01T04:00:00Z",
    ...overrides,
  };
}

/** The reversal the server appends: same batch, opposite quantity, pointing at the original. */
function reversalOf(original: StockMovement): StockMovement {
  return movement({
    id: "mv-correction",
    quantity: -original.quantity,
    type: "ADJUSTMENT",
    reason: "COUNT_CORRECTION",
    referenceType: "CORRECTION",
    referenceId: original.id,
    note: "Counted the wrong batch",
    createdAt: "2026-09-02T05:00:00Z",
  });
}

/** The server's refusal for a movement that already carries a reversal (KMS-400039, a 409). */
function alreadyCorrected(): ApiError {
  return new ApiError(
    {
      code: "KMS-400039",
      message: "This stock movement has already been corrected.",
      action:
        "Look at the correction that was already recorded; if that too is wrong, correct it instead.",
      fieldErrors: [],
    },
    409
  );
}

/**
 * Renders the screen and waits for the history to arrive.
 *
 * <p>`rows` is how many movements the fixture put in it — every fixture here is acted by "Gopal
 * Das", so counting his name counts the rows, and waiting for the *right* number is what stops a
 * two-row fixture asserting against a table that has only painted its first row.
 */
async function openHistory(rows = 1) {
  render(<InventoryItemPage />);
  // Both queries resolve on their own microtasks; the history is the second of them.
  await waitFor(() => expect(screen.getAllByText("Gopal Das")).toHaveLength(rows));
}

/** The row a piece of text sits in, for asking what that one row offers. */
function rowContaining(text: RegExp | string): HTMLElement {
  const cell = screen.getByText(text);
  const row = cell.closest("tr");
  if (!row) throw new Error(`"${text}" is not inside a table row`);
  return row as HTMLElement;
}

describe("correcting a movement, and stopping tracking an item", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_MANAGER", userId: "me", fullName: "Radha" },
      getToken: async () => "test-token",
      refresh: () => {},
    };
    movementsRef.current = [movement()];
    getItemMock.mockReset().mockImplementation(async () => detail());
    listMovementsMock.mockReset().mockImplementation(async () => movementsRef.current);
    compensateMock.mockReset();
    deleteItemMock.mockReset().mockResolvedValue(undefined);
    adjustMock.mockReset();
    pushMock.mockReset();
  });

  it("posts the correction with its note and shows the reversal against the original", async () => {
    const original = movement();
    // What the server does: appends the reverse, cross-referenced, and leaves the original alone.
    compensateMock.mockImplementation(async () => {
      movementsRef.current = [reversalOf(original), original];
      return { id: "mv-correction" };
    });

    await openHistory();

    fireEvent.click(screen.getByRole("button", { name: /^correct$/i }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/stays in the ledger exactly as it is/i)).toBeInTheDocument();

    fireEvent.change(within(dialog).getByRole("textbox"), {
      target: { value: "Counted the wrong batch" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: /record the correction/i }));

    await waitFor(() =>
      expect(compensateMock).toHaveBeenCalledWith("mv-1", "Counted the wrong batch", "test-token")
    );

    // The reversal is on the screen, saying what it reverses rather than showing a hex id...
    await waitFor(() =>
      expect(screen.getByText(/correction of the cooked on/i)).toBeInTheDocument()
    );
    // ...the original is still there, marked, rather than having been edited away...
    expect(screen.getByText("Corrected")).toBeInTheDocument();
    // ...and that row stops offering to correct it a second time, which the server would refuse
    // (KMS-400039) after the reason had already been typed out. The badge is the whole explanation;
    // nothing disabled is left in its place (T-036, following T-002).
    expect(
      within(rowContaining("Corrected")).queryByRole("button", { name: /^correct$/i })
    ).not.toBeInTheDocument();
    // ...and both directions of the movement are now on the ledger.
    expect(screen.getByText("-2 Kg")).toBeInTheDocument();
    expect(screen.getByText("+2 Kg")).toBeInTheDocument();
    // The dialog closed on success.
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("puts the server's refusal on the screen in words when it has already been corrected", async () => {
    compensateMock.mockRejectedValue(alreadyCorrected());

    await openHistory();

    fireEvent.click(screen.getByRole("button", { name: /^correct$/i }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByRole("textbox"), { target: { value: "Second go" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /record the correction/i }));

    // The sentence and its next step, both readable. Not a silent failure, and not a raw dump.
    await waitFor(() =>
      expect(
        screen.getByText(/this stock movement has already been corrected\./i)
      ).toBeInTheDocument()
    );
    expect(
      screen.getByText(/look at the correction that was already recorded/i)
    ).toBeInTheDocument();
    // The code stays quotable but quiet, and the HTTP status never reaches the reader.
    expect(screen.getByText("KMS-400039")).toBeInTheDocument();
    expect(screen.queryByText(/409/)).not.toBeInTheDocument();
    // The control is still on the row, and that is the race being reproduced rather than an
    // oversight. This screen's copy of the history carries no reversal — the other tab's correction
    // has not reached it — so the row has nothing to withhold the control on, and the server is the
    // only thing that knows better. That is the one route left to this branch since T-036 stopped
    // the row itself offering Correct on a movement it can already see corrected.
    expect(screen.getByRole("button", { name: /^correct$/i })).toBeInTheDocument();
  });

  /**
   * T-161: the sentence Form puts beside a refused box. Checked three ways so that "beside" means
   * something: the box is marked invalid, it is described by that very sentence, and the sentence's
   * slot sits straight after the box, or after the label wrapping it.
   */
  function expectSaidBeside(box: HTMLElement, sentence: string) {
    const said = screen.getByText(sentence);
    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(box.getAttribute("aria-describedby")?.split(" ")).toContain(said.id);
    expect((box.closest("label") ?? box).nextElementSibling).toBe(said.parentElement);
  }

  it("names a blank reason for a correction in red beside its box, and sends nothing (T-161)", async () => {
    await openHistory();

    fireEvent.click(screen.getByRole("button", { name: /^correct$/i }));
    const dialog = screen.getByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: /record the correction/i }));

    expectSaidBeside(within(dialog).getByRole("textbox"), "Why it is being corrected is required");
    expect(compensateMock).not.toHaveBeenCalled();
  });

  it("names a blank count in red beside its box, and records nothing (T-161)", async () => {
    await openHistory();

    // No batches in the fixture, so this is the opening count rather than an adjustment to a lot.
    fireEvent.click(screen.getByRole("button", { name: /record what's on the shelf/i }));
    const form = screen.getByRole("form", { name: /adjust stock/i });
    fireEvent.click(within(form).getByRole("button", { name: /record the count/i }));

    // The label is a question, so the sentence reads "How much is there is required". Reported in
    // T-161's proof for Rajeev rather than reworded here.
    expectSaidBeside(within(form).getByLabelText(/how much is there/i), "How much is there is required");
    expect(adjustMock).not.toHaveBeenCalled();
  });

  it("says an opening count below nothing must be at least 0, and records nothing (T-161)", async () => {
    await openHistory();

    fireEvent.click(screen.getByRole("button", { name: /record what's on the shelf/i }));
    const form = screen.getByRole("form", { name: /adjust stock/i });
    fireEvent.change(within(form).getByLabelText(/how much is there/i), { target: { value: "-1" } });
    fireEvent.click(within(form).getByRole("button", { name: /record the count/i }));

    expectSaidBeside(
      within(form).getByLabelText(/how much is there/i),
      "How much is there must be at least 0"
    );
    expect(adjustMock).not.toHaveBeenCalled();
  });

  it("says the unit and the month back exactly as the table above it wrote them", async () => {
    /*
     * Rajeev, 2026-09-07: the table said "+1.8 Kg" on "23 Aug 2026" and the dialog under it
     * answered "+1.8 kg on 23 aug 2026". `summary` is built with the unit and the date already
     * formatted, and both call sites lower-cased the whole sentence to get the leading type label
     * to read mid-sentence — so the label was fixed and the unit and the month were collateral.
     * Units are not decoration here, which `components/planner/MealComposer.tsx:1362` already had
     * to learn: `toLowerCase()` turned a litre's "L" into the digit-like "l".
     */
    movementsRef.current = [
      movement({
        id: "mv-aug",
        quantity: 1.8,
        unit: "KG",
        type: "ADJUSTMENT",
        reason: "COUNT_CORRECTION",
        createdAt: "2026-08-23T06:15:00Z",
      }),
    ];
    await openHistory();

    // What the table prints, which is what the dialog has to agree with.
    expect(screen.getByText("+1.8 Kg")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /^correct$/i }));
    const dialog = screen.getByRole("dialog");
    const sentence =
      within(dialog).getByText(/stays in the ledger exactly as it is/i).textContent ?? "";

    expect(sentence).toContain("+1.8 Kg");
    expect(sentence).toContain("Aug 2026");
    expect(sentence).not.toContain("kg");
    expect(sentence).not.toContain("aug");
    // The label still reads mid-sentence — "The adjustment of…", not "The Adjustment of…".
    expect(sentence).toContain("The adjustment of");
  });

  it("keeps a litre a capital L in the dialog, which is the case that means something", async () => {
    movementsRef.current = [
      movement({
        id: "mv-oil",
        ingredientName: "Groundnut oil",
        quantity: -2.5,
        unit: "L",
        createdAt: "2026-08-23T06:15:00Z",
      }),
    ];
    await openHistory();

    expect(screen.getByText("-2.5 L")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /^correct$/i }));
    const sentence =
      within(screen.getByRole("dialog")).getByText(/stays in the ledger exactly as it is/i)
        .textContent ?? "";

    // "2.5 l" is what the defect printed, and it reads as a digit next to the number it follows.
    expect(sentence).toContain("-2.5 L");
    expect(sentence).not.toContain("-2.5 l");
    expect(sentence).toContain("The cooked of");
  });

  it("offers Correct on a movement nothing has reversed, and not on one already corrected", async () => {
    const original = movement();
    // The state a page load finds after somebody else corrected it: both rows, cross-referenced.
    movementsRef.current = [reversalOf(original), original];
    await openHistory(2);

    // The original carries the badge and no control — a person who reads "Corrected" is not then
    // invited to correct it and refused after writing out a reason.
    const corrected = rowContaining("Corrected");
    expect(within(corrected).queryByRole("button", { name: /^correct$/i })).not.toBeInTheDocument();
    // And nothing disabled sits there instead: the actions cell of that row holds no button at all.
    expect(within(corrected).queryAllByRole("button")).toHaveLength(0);

    // The correction itself has nothing against it, so it is still correctable — which is what the
    // server says to do when a correction is the thing that was wrong.
    const reversal = rowContaining(/correction of the cooked on/i);
    expect(within(reversal).getByRole("button", { name: /^correct$/i })).toBeInTheDocument();
  });

  it("says plainly that the ledger survives before it stops tracking, then returns to the list", async () => {
    await openHistory();

    fireEvent.click(screen.getByRole("button", { name: /stop tracking this item/i }));
    const dialog = screen.getByRole("dialog");

    expect(within(dialog).getByText(/stop tracking toor dal\?/i)).toBeInTheDocument();
    // What it does...
    expect(within(dialog).getByText(/comes off the inventory list/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/low-stock warnings/i)).toBeInTheDocument();
    // ...and, the half a person cannot otherwise know, what it does not do.
    expect(within(dialog).getByText(/every movement stays in the ledger/i)).toBeInTheDocument();
    expect(within(dialog).getByText(/is erased/i)).toBeInTheDocument();

    fireEvent.click(within(dialog).getByRole("button", { name: /stop tracking it/i }));

    await waitFor(() => expect(deleteItemMock).toHaveBeenCalledWith("item-1", "test-token"));
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/inventory"));

    // The metadata-only delete, and nothing else. Writing the stock down to zero first would put a
    // loss in the ledger that never happened — the confusion behind outstanding item I2.
    expect(adjustMock).not.toHaveBeenCalled();
  });

  it("keeps the item when the confirmation is cancelled", async () => {
    await openHistory();

    fireEvent.click(screen.getByRole("button", { name: /stop tracking this item/i }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: /cancel/i }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(deleteItemMock).not.toHaveBeenCalled();
    expect(pushMock).not.toHaveBeenCalled();
  });
});

/*
 * T-086. Taking `Reorder at` off the inventory table left "why does this say Low" answerable
 * nowhere, because the level was on no other screen; and committed as a bare total on the table
 * begs the question the total cannot answer. Both land here, which is why they are tested here.
 */
describe("the three figures, the level they are judged against, and who claimed the stock", () => {
  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "KITCHEN_MANAGER", userId: "me", fullName: "Radha" },
      getToken: async () => "test-token",
      refresh: () => {},
    };
    movementsRef.current = [];
    listMovementsMock.mockReset().mockImplementation(async () => movementsRef.current);
    compensateMock.mockReset();
    deleteItemMock.mockReset().mockResolvedValue(undefined);
    adjustMock.mockReset();
    pushMock.mockReset();
    getItemMock.mockReset().mockImplementation(async () => detail());
  });

  it("shows the reorder level, which the inventory table no longer carries", async () => {
    getItemMock.mockImplementation(async () =>
      detail({ item: itemView({ onHand: 50, committed: 30, available: 20, reorderThreshold: 25 }) })
    );
    render(<InventoryItemPage />);

    expect(await screen.findByText("Reorder level")).toBeInTheDocument();
    expect(screen.getByText("25 Kg")).toBeInTheDocument();

    // And beside it the working: 50 on hand, 30 spoken for, 20 left. Four numbers next to each
    // other are what make "why does this say Low" answerable without a word of explanation.
    expect(screen.getByText("50 Kg")).toBeInTheDocument();
    expect(screen.getByText("30 Kg")).toBeInTheDocument();
    expect(screen.getByText("20 Kg")).toBeInTheDocument();
  });

  it("says Not set rather than a made-up number where no level has been chosen", async () => {
    getItemMock.mockImplementation(async () => detail({ item: itemView({ reorderThreshold: null }) }));
    render(<InventoryItemPage />);

    expect(await screen.findByText("Not set")).toBeInTheDocument();
  });

  it("shows the meals that committed the stock, each linking to its meal in the planner", async () => {
    getItemMock.mockImplementation(async () =>
      detail({
        item: itemView({ onHand: 50, committed: 30, available: 20 }),
        committed: [
          {
            dishId: "dish-1",
            mealId: "meal-1",
            planDate: "2026-09-11",
            mealKind: "Lunch",
            eventName: null,
            recipeName: "Khichadi",
            quantity: 18,
            unit: "KG",
          },
          {
            dishId: "dish-2",
            mealId: "meal-2",
            planDate: "2026-09-13",
            mealKind: "Event",
            eventName: "Saturday reading",
            recipeName: "Sweet pongal",
            quantity: 12,
            unit: "KG",
          },
        ],
      })
    );
    render(<InventoryItemPage />);

    expect(await screen.findByRole("heading", { name: /committed to meals/i })).toBeInTheDocument();
    expect(screen.getByText("Khichadi")).toBeInTheDocument();
    expect(screen.getByText("18 Kg")).toBeInTheDocument();

    // The link is the whole point: "we cannot spare that" is always answered by an edit to the meal.
    // By the meal's own id since D-27, not by its day — a day can hold two events. Matched on the
    // href rather than on the month's name, which is a formatting question and belongs to
    // `dateWithYear`'s own tests.
    const meals = screen
      .getAllByRole("link")
      .map((a) => a.getAttribute("href"))
      // A meal, not the sidebar's "Reuse a plan" — /planner/ has more under it than meals.
      .filter((href) => /^\/planner\/meal\//.test(href ?? ""));
    expect(meals).toEqual(["/planner/meal/meal-1", "/planner/meal/meal-2"]);

    // An event is shown by the name people recognise, not by the word "Event".
    expect(screen.getByText("Saturday reading")).toBeInTheDocument();
  });

  it("names the two ways of being Low apart, so an over-promised item is not read as running out", async () => {
    getItemMock.mockImplementation(async () =>
      detail({
        item: itemView({ onHand: 415.41, committed: 420, available: -4.59, reorderThreshold: null, belowThreshold: true }),
      })
    );
    render(<InventoryItemPage />);

    expect(await screen.findByText(/more committed than you hold/i)).toBeInTheDocument();
    expect(screen.queryByText(/below reorder level/i)).not.toBeInTheDocument();
  });
});

/*
 * R-ING-3 on this screen (T-257): any adjustment that adds stock, whatever its reason, asks what the
 * stock would cost to buy today, pre-filled and required; taking stock away asks nothing.
 */
describe("the value asked when an adjustment adds stock (R-ING-3)", () => {
  const batch = { batchId: "b1", quantity: 8, unit: "KG", expiryDate: null, receivedDate: "2026-09-01", expiringSoon: false };

  beforeEach(() => {
    authRef.current = {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me", fullName: "Radha" },
      getToken: async () => "test-token",
      refresh: () => {},
    };
    movementsRef.current = [];
    listMovementsMock.mockReset().mockImplementation(async () => movementsRef.current);
    getItemMock.mockReset().mockImplementation(async () => detail({ batches: [batch] }));
    adjustMock.mockReset().mockResolvedValue({ id: "mv-new" });
    suggestMock.mockReset().mockResolvedValue({ pricePerUnit: 60, source: "MARKET_RATE" });
  });

  function valueBox(form: HTMLElement) {
    return within(form).queryByLabelText(/what it would cost to buy today/i, { selector: "input" });
  }

  async function openAdjust(button: RegExp) {
    render(<InventoryItemPage />);
    fireEvent.click(await screen.findByRole("button", { name: button }));
    return screen.getByRole("form", { name: /adjust stock/i });
  }

  it("does not ask when stock is taken away, and sends no value", async () => {
    const form = await openAdjust(/adjust stock/i);
    fireEvent.change(within(form).getByLabelText(/change/i), { target: { value: "-2" } });
    expect(valueBox(form)).toBeNull();

    fireEvent.click(within(form).getByRole("button", { name: /record adjustment/i }));
    await waitFor(() => expect(adjustMock).toHaveBeenCalledTimes(1));
    expect(adjustMock.mock.calls[0][1]).toMatchObject({ batchId: "b1", quantity: -2, pricePerUnit: null });
  });

  it("asks for any reason that adds stock, pre-filled per the stock unit, and sends it", async () => {
    const form = await openAdjust(/adjust stock/i);
    // Spoilage is the default reason, and a positive one still adds stock (the conductor's ruling).
    fireEvent.change(within(form).getByLabelText(/change/i), { target: { value: "3" } });

    const box = valueBox(form)!;
    expect(box).toBeRequired();
    expect(within(form).getByText("What it would cost to buy today (₹ per Kg)")).toBeInTheDocument();
    await waitFor(() => expect(box).toHaveValue(60));
    expect(suggestMock).toHaveBeenCalledWith("ing-1", "test-token");

    fireEvent.click(within(form).getByRole("button", { name: /record adjustment/i }));
    await waitFor(() => expect(adjustMock).toHaveBeenCalledTimes(1));
    expect(adjustMock.mock.calls[0][1]).toMatchObject({ quantity: 3, reason: "SPOILAGE", pricePerUnit: 60 });
  });

  it("refuses a blank value in red beside the box, and records nothing", async () => {
    suggestMock.mockResolvedValue({ pricePerUnit: null, source: null });
    const form = await openAdjust(/adjust stock/i);
    fireEvent.change(within(form).getByLabelText(/reason/i), { target: { value: "COUNT_CORRECTION" } });
    fireEvent.change(within(form).getByLabelText(/change/i), { target: { value: "3" } });
    await waitFor(() => expect(suggestMock).toHaveBeenCalled());

    fireEvent.click(within(form).getByRole("button", { name: /record adjustment/i }));

    const box = valueBox(form)!;
    const said = screen.getByText("What it would cost to buy today (₹ per Kg) is required");
    expect(box).toHaveAttribute("aria-invalid", "true");
    expect(box.getAttribute("aria-describedby")?.split(" ")).toContain(said.id);
    expect(adjustMock).not.toHaveBeenCalled();
  });

  it("asks on the first count too, since that adds stock", async () => {
    getItemMock.mockImplementation(async () => detail());
    const form = await openAdjust(/record what's on the shelf/i);
    expect(valueBox(form)).toBeNull();
    fireEvent.change(within(form).getByLabelText(/how much is there/i), { target: { value: "40" } });
    await waitFor(() => expect(valueBox(form)).toHaveValue(60));

    fireEvent.click(within(form).getByRole("button", { name: /record the count/i }));
    await waitFor(() => expect(adjustMock).toHaveBeenCalledTimes(1));
    expect(adjustMock.mock.calls[0][1]).toMatchObject({ batchId: null, quantity: 40, pricePerUnit: 60 });
  });
});
