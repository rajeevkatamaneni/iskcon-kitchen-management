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
    reorderThreshold: 5,
    belowThreshold: false,
    expiringSoon: false,
    soonestExpiry: null,
    notes: null,
    ...overrides,
  };
}

function detail(): StockDetail {
  return { item: itemView(), batches: [] };
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

async function openHistory() {
  render(<InventoryItemPage />);
  // Both queries resolve on their own microtasks; the history is the second of them.
  await waitFor(() => expect(screen.getByText("Gopal Das")).toBeInTheDocument());
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
    // The control was never hidden: it is still there to be pressed again.
    expect(screen.getByRole("button", { name: /^correct$/i })).toBeInTheDocument();
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
