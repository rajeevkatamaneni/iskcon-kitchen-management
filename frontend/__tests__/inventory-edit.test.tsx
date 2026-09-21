import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { StockDetail, StockItemView } from "@/lib/api";

/**
 * Reading an item, then changing it (T-440).
 *
 * <p>Rajeev, 2026-09-20: *"When the user clicks on the Ingredient Name, it opens in the view mode,
 * then they see the edit button, click on that and it goes to the edit screen which shows save and
 * cancel."* Two screens, so two describes: what the record has to show, and what the form has to
 * carry over from the inline row it replaced. The three fields are the whole of what that row could
 * change, and the assertions below are written so that dropping any one of them fails.
 *
 * <p>The real `useAuthedQuery` runs, driven by mocked `api` methods, exactly as the correction test
 * does: the record makes two independent queries and a single stubbed hook would answer both with
 * the same object.
 */
const { authRef, getItemMock, updateItemMock, listMovementsMock, suggestMock, pushMock, replaceMock } =
  vi.hoisted(() => ({
    authRef: {
      current: {
        status: "signed-in",
        appUser: { role: "KITCHEN_MANAGER", userId: "me", fullName: "Radha" },
        getToken: async () => "test-token",
        refresh: () => {},
      },
    },
    getItemMock: vi.fn(),
    updateItemMock: vi.fn(),
    listMovementsMock: vi.fn(),
    suggestMock: vi.fn(),
    pushMock: vi.fn(),
    replaceMock: vi.fn(),
  }));

const searchRef = { current: new URLSearchParams() };

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useParams: () => ({ id: "item-1" }),
  useSearchParams: () => searchRef.current,
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
      updateInventoryItem: updateItemMock,
      listMovements: listMovementsMock,
      getStockValueSuggestion: suggestMock,
    },
  };
});

import InventoryItemPage from "@/app/inventory/[id]/page";
import EditInventoryItemPage from "@/app/inventory/[id]/edit/page";

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

function detail(overrides: Partial<StockItemView> = {}): StockDetail {
  return { item: itemView(overrides), batches: [], committed: [] };
}

/**
 * One fact off the record's card, by the word above it.
 *
 * <p>Read through its `dt` rather than by text, because the card legitimately says the same word
 * twice — "None" is both an empty note and nothing on order — and `getByText` would find whichever
 * came first and pass for the wrong reason.
 */
function fact(container: HTMLElement, label: string): HTMLElement | null {
  const term = [...container.querySelectorAll("dt")].find((d) => d.textContent === label);
  return (term?.parentElement?.querySelector("dd") as HTMLElement) ?? null;
}

beforeEach(() => {
  searchRef.current = new URLSearchParams();
  getItemMock.mockReset().mockResolvedValue(detail());
  updateItemMock.mockReset().mockResolvedValue(undefined);
  listMovementsMock.mockReset().mockResolvedValue([]);
  suggestMock.mockReset().mockResolvedValue({ pricePerUnit: null });
  pushMock.mockReset();
  replaceMock.mockReset();
});

describe("the item's own page, in view mode", () => {
  it("offers Edit, and offers no form of its own", async () => {
    render(<InventoryItemPage />);

    const edit = await screen.findByRole("link", { name: "Edit" });
    expect(edit).toHaveAttribute("href", "/inventory/item-1/edit");
    // Read-only: nothing on this screen changes these three without going through Edit.
    expect(screen.queryByRole("textbox", { name: /where is it stored/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("spinbutton", { name: /drops below/i })).not.toBeInTheDocument();
  });

  /*
   * Everything the inline row could change has to be readable here, or the list has lost it: the
   * level, where it is stored and the note were all only ever visible inside that row.
   */
  it("shows where it is kept, the level with its unit, and the note", async () => {
    getItemMock.mockResolvedValue(
      detail({ storageLocation: "Cold room", reorderThreshold: 5, notes: "Keep the lid on tight" })
    );
    const { container } = render(<InventoryItemPage />);

    await screen.findByRole("link", { name: "Edit" });
    expect(fact(container, "Where it is stored")).toHaveTextContent("Cold room");
    expect(fact(container, "Notes")).toHaveTextContent("Keep the lid on tight");
    expect(fact(container, "Reorder level")).toHaveTextContent("5 Kg");
  });

  /* The absence is an answer too, and a blank where a fact should be is not one. */
  it("says so where nothing is recorded, rather than showing a gap", async () => {
    getItemMock.mockResolvedValue(detail({ storageLocation: null, notes: null }));
    const { container } = render(<InventoryItemPage />);

    await screen.findByRole("link", { name: "Edit" });
    expect(fact(container, "Where it is stored")).toHaveTextContent("Not recorded");
    expect(fact(container, "Notes")).toHaveTextContent("None");
  });

  it("shows the confirmation the edit screen sends back, and strips the param", async () => {
    searchRef.current = new URLSearchParams("saved=Toor%20Dal");
    render(<InventoryItemPage />);

    expect(await screen.findByText(/Toor Dal is up to date/i)).toBeInTheDocument();
    expect(replaceMock).toHaveBeenCalledWith("/inventory/item-1");
  });
});

describe("the edit screen", () => {
  const level = (name: string) =>
    screen.getByLabelText(`Tell me when ${name} drops below`, { selector: "input" });
  const save = () => fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

  it("opens holding what is on file, with Save and Cancel", async () => {
    getItemMock.mockResolvedValue(
      detail({ storageLocation: "Cold room", reorderThreshold: 5, notes: "Keep the lid on tight" })
    );
    render(<EditInventoryItemPage />);

    expect(await screen.findByRole("textbox", { name: "Where is it stored" })).toHaveValue("Cold room");
    expect(level("Toor Dal")).toHaveValue(5);
    expect(screen.getByRole("textbox", { name: "Notes" })).toHaveValue("Keep the lid on tight");
    expect(screen.getByRole("button", { name: "Save changes" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/inventory/item-1");
  });

  it("saves all three and goes back to the record with the confirmation", async () => {
    render(<EditInventoryItemPage />);
    await screen.findByRole("textbox", { name: "Where is it stored" });

    fireEvent.change(screen.getByRole("textbox", { name: "Where is it stored" }), {
      target: { value: "Cold room" },
    });
    fireEvent.change(level("Toor Dal"), { target: { value: "12" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Notes" }), {
      target: { value: "Second shelf" },
    });
    save();

    await waitFor(() => expect(updateItemMock).toHaveBeenCalledTimes(1));
    expect(updateItemMock.mock.calls[0][0]).toBe("item-1");
    expect(updateItemMock.mock.calls[0][1]).toEqual({
      storageLocation: "Cold room",
      reorderThreshold: 12,
      notes: "Second shelf",
    });
    // No unit travels with it: this screen cannot change the unit, so the figure is already in the
    // ingredient's own one and the server is told nothing to convert.
    expect(updateItemMock.mock.calls[0][1]).not.toHaveProperty("reorderThresholdUnit");
    expect(pushMock).toHaveBeenCalledWith("/inventory/item-1?saved=Toor%20Dal");
  });

  it("clears the level and the note when they are emptied", async () => {
    getItemMock.mockResolvedValue(detail({ reorderThreshold: 5, notes: "Old note" }));
    render(<EditInventoryItemPage />);
    await screen.findByRole("textbox", { name: "Notes" });

    fireEvent.change(level("Toor Dal"), { target: { value: "" } });
    fireEvent.change(screen.getByRole("textbox", { name: "Notes" }), { target: { value: "  " } });
    save();

    await waitFor(() => expect(updateItemMock).toHaveBeenCalledTimes(1));
    expect(updateItemMock.mock.calls[0][1]).toMatchObject({ reorderThreshold: null, notes: null });
  });

  /* T-424 and T-431, carried over from the row this screen replaced. */
  it("steps by 1 for a counted ingredient and by any for a weighed one", async () => {
    getItemMock.mockResolvedValue(detail({ ingredientName: "Apron", unit: "PIECES" }));
    render(<EditInventoryItemPage />);
    await screen.findByRole("textbox", { name: "Notes" });

    expect(level("Apron")).toHaveAttribute("step", "1");
    expect(level("Apron")).toHaveAttribute("inputmode", "numeric");
  });

  it("refuses a fractional level on a counted ingredient, in the server's words, and does not save", async () => {
    getItemMock.mockResolvedValue(detail({ ingredientName: "Apron", unit: "PIECES" }));
    render(<EditInventoryItemPage />);
    await screen.findByRole("textbox", { name: "Notes" });

    fireEvent.change(level("Apron"), { target: { value: "3.6" } });
    save();

    expect(await screen.findByText("Apron is counted in whole pieces")).toHaveClass("text-danger");
    expect(updateItemMock).not.toHaveBeenCalled();
    // Still on screen holding what was typed, to be corrected rather than retyped.
    expect(level("Apron")).toHaveValue(3.6);
  });

  it("still takes a fractional level on a weighed ingredient", async () => {
    render(<EditInventoryItemPage />);
    await screen.findByRole("textbox", { name: "Notes" });

    expect(level("Toor Dal")).toHaveAttribute("step", "any");
    fireEvent.change(level("Toor Dal"), { target: { value: "3.6" } });
    save();

    await waitFor(() => expect(updateItemMock).toHaveBeenCalledTimes(1));
    expect(updateItemMock.mock.calls[0][1]).toMatchObject({ reorderThreshold: 3.6 });
  });

  it("still shows a fractional level that is already on file", async () => {
    getItemMock.mockResolvedValue(
      detail({ ingredientName: "Apron", unit: "PIECES", reorderThreshold: 3.6 })
    );
    render(<EditInventoryItemPage />);
    await screen.findByRole("textbox", { name: "Notes" });

    expect(level("Apron")).toHaveValue(3.6);
  });

  it("names the unit the level is in, and offers no way to change it", async () => {
    render(<EditInventoryItemPage />);
    await screen.findByRole("textbox", { name: "Notes" });

    expect(screen.getByText("Kg")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("refuses a role without inventory access", () => {
    authRef.current = {
      ...authRef.current,
      appUser: { role: "VOLUNTEER", userId: "me", fullName: "Radha" },
    };
    render(<EditInventoryItemPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
    authRef.current = {
      ...authRef.current,
      appUser: { role: "KITCHEN_MANAGER", userId: "me", fullName: "Radha" },
    };
  });
});
