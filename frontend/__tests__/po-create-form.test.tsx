import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientView, VendorDetailView, VendorSupplyView, VendorView } from "@/lib/api";
import { todayIso } from "@/lib/format";

/**
 * "Create a purchase order" — the one form (R-PO-1, R-PO-2, R-PO-3; T-263).
 *
 * <p>Design A of the `dev-po` mock: vendor beside Needed by, "Note for the vendor" growing as it is
 * typed into, then "Items" — an inline table whose last row is always an empty type-ahead. Each
 * acceptance criterion in R-PO-3 has a test here, by keyboard and by mouse, and so does each thing
 * R-PO-2 says must be gone.
 *
 * <p>`useAuthedQuery` is not mocked, as in `manual-purchase-order.test.tsx`: the screen depends on
 * its real behaviour (a fetcher that is not stable re-fetches for ever, and the vendor detail is
 * re-fetched when the vendor changes). The API is mocked, which is where the network would be.
 */

const { authRef, pushMock, paramsRef, listVendors, listIngredients, getVendor, createPurchaseOrder } = vi.hoisted(
  () => ({
    authRef: {
      current: {
        status: "signed-in",
        appUser: {
          userId: "u1",
          fullName: "Gopal Das",
          role: "KITCHEN_STAFF",
          tenantName: "Bengaluru Temple",
          tenantSlug: "bengaluru",
          temples: [],
        },
        getToken: async () => "test-token",
        refresh: () => {},
        signOut: vi.fn(),
        switchTemple: vi.fn(),
      } as Record<string, unknown>,
    },
    pushMock: vi.fn(),
    paramsRef: { current: new URLSearchParams() },
    listVendors: vi.fn(),
    listIngredients: vi.fn(),
    getVendor: vi.fn(),
    createPurchaseOrder: vi.fn(),
  })
);

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({}),
  usePathname: () => "/orders/new",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, listVendors, listIngredients, getVendor, createPurchaseOrder },
  };
});

import NewPurchaseOrderPage from "@/app/orders/new/page";

function vendor(o: Partial<VendorView> = {}): VendorView {
  return {
    id: "v1",
    name: "Kalasipalya Vegetable Mandi",
    contactPerson: null,
    phone: "+919812345678",
    email: null,
    address: null,
    gstin: null,
    preferredLanguage: "kn",
    notes: null,
    contractEndDate: null,
    contractEndingSoon: false,
    active: true,
    whatsappReachable: true,
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

function ingredient(id: string, name: string, unit: string): IngredientView {
  return {
    id, name, category: "Vegetables", unit,
    packSizes: [], marketRate: null, marketRateOn: null, marketRateSource: null,
    ekadashiProhibited: false, supply: false, notBought: false, libraryDerived: false, aliases: [], createdAt: "2026-01-01T00:00:00Z",
  };
}

function supply(ingredientId: string, ingredientName: string, unit: string, lastPrice: number | null): VendorSupplyView {
  return {
    ingredientId, ingredientName, unit, lastPrice,
    packSizeId: null, packLabel: null, pricePerPack: null,
    previousPrice: null, previousPriceOn: null, leadTimeDays: null, preferred: false,
  };
}

const INGREDIENTS = [
  ingredient("tom", "Tomato, ripe", "KG"),
  ingredient("car", "Cardamom", "GM"),
  ingredient("cas", "Cashew, whole", "KG"),
  ingredient("tpu", "Tomato puree", "KG"),
];

// Tomato at ₹32 a Kg, and cardamom kept per gram — ₹0.068 a gram, which the list must say per Kg.
const DETAIL: VendorDetailView = {
  vendor: vendor(),
  supplies: [supply("tom", "Tomato, ripe", "KG", 32), supply("car", "Cardamom", "GM", 0.068)],
  statusHistory: [],
};

function tomorrow(): string {
  const d = new Date(`${todayIso()}T00:00:00`);
  d.setDate(d.getDate() + 1);
  return d.toISOString().slice(0, 10);
}

beforeEach(() => {
  paramsRef.current = new URLSearchParams();
  pushMock.mockReset();
  listVendors.mockReset().mockResolvedValue([vendor(), vendor({ id: "v2", name: "Sri Lakshmi Provisions" })]);
  listIngredients.mockReset().mockResolvedValue(INGREDIENTS);
  getVendor.mockReset().mockResolvedValue(DETAIL);
  createPurchaseOrder.mockReset().mockResolvedValue({ id: "po-new", poNumber: "PO-2026-0044" });
});

/** Opens the form and chooses Kalasipalya, then waits until its own items are in the list. */
async function openWithVendor() {
  render(<NewPurchaseOrderPage />);
  const picker = await screen.findByRole("combobox", { name: "Vendor" });
  await waitFor(() => expect(within(picker).getAllByRole("option")).toHaveLength(3));
  fireEvent.change(picker, { target: { value: "v1" } });
  await waitFor(() => expect(getVendor).toHaveBeenCalledWith("v1", "test-token"));
  const box = screen.getByRole("combobox", { name: "Add an item" });
  // The vendor's detail and the ingredient list arrive on their own; wait for both to be offered.
  // Typed once; the open list re-renders as each answer lands.
  fireEvent.change(box, { target: { value: "ca" } });
  await waitFor(() => expect(listed()).toEqual(["Cardamom · ₹68/Kg", "Cashew, whole · Kg"]));
  fireEvent.change(box, { target: { value: "" } });
  return box;
}

/** The type-ahead's options, as read. Scoped to its listbox: the vendor <select> has options too. */
const listed = () => within(screen.getByRole("listbox")).getAllByRole("option").map((o) => o.textContent);

const orderTotal = () => screen.getByTestId("order-total").textContent;

describe("straight to the form (R-PO-1)", () => {
  it("is the create form itself — no step asking only for the vendor", async () => {
    render(<NewPurchaseOrderPage />);

    expect(await screen.findByRole("heading", { name: "Create a purchase order" })).toBeInTheDocument();
    const form = await screen.findByRole("form", { name: "Create a purchase order" });
    expect(within(form).getByRole("combobox", { name: "Vendor" })).toBeInTheDocument();
    expect(within(form).getByLabelText("Needed by")).toBeInTheDocument();
    expect(within(form).getByRole("combobox", { name: "Add an item" })).toBeInTheDocument();

    expect(screen.queryByRole("button", { name: /continue/i })).toBeNull();
    expect(screen.queryByRole("link", { name: /add a vendor/i })).toBeNull();
    expect(screen.getByRole("button", { name: "Create order" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/orders");
  });

  it("asks the server for active vendors only, with the flag the right way round", async () => {
    render(<NewPurchaseOrderPage />);
    await screen.findByRole("combobox", { name: "Vendor" });
    expect(listVendors).toHaveBeenCalledWith(true, "test-token");
  });

  it("chooses a vendor named in the address, if it is an active one", async () => {
    paramsRef.current = new URLSearchParams("vendor=v2");
    render(<NewPurchaseOrderPage />);
    const picker = await screen.findByRole("combobox", { name: "Vendor" });
    await waitFor(() => expect(picker).toHaveValue("v2"));
  });

  it("ignores a vendor in the address that is not active", async () => {
    paramsRef.current = new URLSearchParams("vendor=v-dropped");
    render(<NewPurchaseOrderPage />);
    const picker = await screen.findByRole("combobox", { name: "Vendor" });
    await waitFor(() => expect(within(picker).getAllByRole("option")).toHaveLength(3));
    expect(picker).toHaveValue("");
  });
});

describe("the header (R-PO-2)", () => {
  it("has Vendor and Needed by in one row, Note for the vendor after, and no Deliver to", async () => {
    render(<NewPurchaseOrderPage />);
    const vendorBox = await screen.findByRole("combobox", { name: "Vendor" });
    const needed = screen.getByLabelText("Needed by");
    // One grid row of two columns holds both. jsdom has no layout, so the geometry is measured in a
    // browser and recorded in the proof; this pins the structure that produces it.
    const row = vendorBox.closest("label")!.parentElement!;
    expect(row).toBe(needed.closest("label")!.parentElement);
    expect(row.className).toMatch(/sm:grid-cols-2/);

    expect(screen.queryByText(/deliver to/i)).toBeNull();
    expect(screen.queryByPlaceholderText("Main store")).toBeNull();
    expect(screen.getByRole("heading", { name: "Items" })).toBeInTheDocument();
    expect(screen.queryByText(/^Lines$/)).toBeNull();
  });

  it("has a note that is a textarea and grows as it is typed into", async () => {
    render(<NewPurchaseOrderPage />);
    const note = (await screen.findByLabelText("Note for the vendor")) as HTMLTextAreaElement;
    expect(note.tagName).toBe("TEXTAREA");
    expect(note).toHaveAttribute("rows", "1");

    // jsdom has no layout, so scrollHeight is faked: what is tested is that the box is resized to
    // its content on every change, which is the whole of "grows as the user types".
    Object.defineProperty(note, "scrollHeight", { configurable: true, get: () => 120 });
    fireEvent.change(note, { target: { value: "Line one\nLine two\nLine three\nLine four" } });
    expect(note.style.height).toBe("122px");
  });

  it("says nothing like “Nothing on this order yet.” when there are no items", async () => {
    render(<NewPurchaseOrderPage />);
    await screen.findByRole("combobox", { name: "Add an item" });
    expect(screen.queryByText(/nothing on this order yet/i)).toBeNull();
  });
});

describe("the items table (R-PO-3)", () => {
  it("has the columns Item · Quantity · Unit · Expected price (₹) · Line total · remove, and the hint above it", async () => {
    render(<NewPurchaseOrderPage />);
    await screen.findByRole("combobox", { name: "Add an item" });
    const headers = screen.getAllByRole("columnheader").map((h) => h.textContent);
    expect(headers).toEqual(["Item", "Quantity", "Unit", "Expected price (₹)", "Line total", "Remove"]);
    expect(
      screen.getByText("Start typing an ingredient, or type anything to add a one-off item.")
    ).toBeInTheDocument();
  });

  it("lists the vendor’s items first with their list price, then Other ingredients", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "tom" } });
    expect(listed()).toEqual([
      "Tomato, ripe · ₹32/Kg",
      "Tomato puree · Kg",
    ]);
    const groups = within(screen.getByRole("listbox")).getAllByRole("group");
    expect(groups[0]).toHaveAccessibleName("Sold by Kalasipalya Vegetable Mandi");
    expect(groups[1]).toHaveAccessibleName("Other ingredients");
  });

  it("catalogue item by keyboard: fills Unit and Expected price, focuses the quantity, keeps an empty row", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "tom" } });
    fireEvent.keyDown(box, { key: "Enter" });

    const qty = screen.getByLabelText("Quantity of Tomato, ripe");
    await waitFor(() => expect(qty).toHaveFocus());
    expect(screen.getByLabelText("Expected price of Tomato, ripe")).toHaveValue(32);
    const row = qty.closest("tr")!;
    expect(within(row).getByText("Kg")).toBeInTheDocument();
    // An ingredient with no pack sizes has one unit to be ordered in, so it is not a choice.
    expect(within(row).queryByRole("combobox")).toBeNull();

    // The empty row is still there, below it, and cleared.
    const again = screen.getByRole("combobox", { name: "Add an item" });
    expect(again).toHaveValue("");
    expect(again.closest("tr")!.previousElementSibling).toBe(row);
    // Once on the order, it is not offered twice.
    fireEvent.change(again, { target: { value: "tom" } });
    expect(listed()).toEqual(["Tomato puree · Kg"]);
  });

  it("catalogue item by mouse: a price kept per gram is filled per Kg, beside Kg (T-288, D-2)", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "card" } });
    fireEvent.mouseDown(within(screen.getByRole("listbox")).getByRole("option", { name: /Cardamom/ }));

    // ₹0.068 a gram is ₹68 a Kg, the figure the list said beside it ("Cardamom · ₹68/Kg").
    const price = screen.getByLabelText("Expected price of Cardamom");
    expect(price).toHaveValue(68);
    expect(price).toHaveAttribute("step", "any");
    const row = price.closest("tr")!;
    expect(within(row).getByText("Kg")).toBeInTheDocument();
    expect(within(row).queryByText("gm")).toBeNull();
    expect(within(row).getByTestId("rate-note")).toHaveTextContent("₹68 / Kg");
  });

  it("an ingredient the vendor does not sell comes with its unit and no price", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "cash" } });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(screen.getByLabelText("Expected price of Cashew, whole")).toHaveValue(null);
  });

  it("one-off by keyboard: a free-text line with a unit select and a typed price", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "Plastic stool" } });
    expect(listed()).toEqual([
      "Add ‘Plastic stool’ as a one-off item",
    ]);
    fireEvent.keyDown(box, { key: "Enter" });

    const qty = screen.getByLabelText("Quantity of Plastic stool");
    await waitFor(() => expect(qty).toHaveFocus());
    const row = qty.closest("tr")!;
    expect(within(row).getByText("One-off item")).toBeInTheDocument();
    const unit = within(row).getByRole("combobox", { name: "Unit for Plastic stool" });
    expect(unit).toHaveValue("PIECES");
    fireEvent.change(unit, { target: { value: "KG" } });
    expect(unit).toHaveValue("KG");
    expect(screen.getByLabelText("Expected price of Plastic stool")).toHaveValue(null);
  });

  it("one-off by mouse", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "Gas cylinder" } });
    fireEvent.mouseDown(within(screen.getByRole("listbox")).getByRole("option", { name: "Add ‘Gas cylinder’ as a one-off item" }));
    expect(screen.getByLabelText("Quantity of Gas cylinder")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Unit for Gas cylinder" })).toBeInTheDocument();
  });

  it("Escape closes the list without adding anything", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "tom" } });
    fireEvent.keyDown(box, { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(screen.queryByLabelText("Quantity of Tomato, ripe")).toBeNull();
  });

  it("updates each line total and the order total as quantities are typed", async () => {
    const box = await openWithVendor();
    expect(orderTotal()).toBe("₹0");
    expect(screen.getByText("0 items")).toBeInTheDocument();

    fireEvent.change(box, { target: { value: "tom" } });
    fireEvent.keyDown(box, { key: "Enter" });
    const tomatoQty = screen.getByLabelText("Quantity of Tomato, ripe");
    const tomatoRow = tomatoQty.closest("tr")!;
    fireEvent.change(tomatoQty, { target: { value: "9" } });
    expect(within(tomatoRow).getByText("₹288")).toBeInTheDocument();
    expect(orderTotal()).toBe("₹288");

    fireEvent.change(screen.getByRole("combobox", { name: "Add an item" }), { target: { value: "card" } });
    fireEvent.keyDown(screen.getByRole("combobox", { name: "Add an item" }), { key: "Enter" });
    fireEvent.change(screen.getByLabelText("Quantity of Cardamom"), { target: { value: "0.5" } });
    // 0.5 Kg at ₹68 a Kg (T-288: typed in Kg, priced per Kg).
    expect(within(screen.getByLabelText("Quantity of Cardamom").closest("tr")!).getByText("₹34")).toBeInTheDocument();
    expect(orderTotal()).toBe("₹322");
    expect(screen.getByText("2 items")).toBeInTheDocument();

    fireEvent.change(tomatoQty, { target: { value: "10" } });
    expect(orderTotal()).toBe("₹354");

    fireEvent.click(screen.getByRole("button", { name: "Remove Tomato, ripe" }));
    expect(orderTotal()).toBe("₹34");
    expect(screen.getByText("1 item")).toBeInTheDocument();
  });
});

it("rounds a line total to paise, so 300 at ₹0.07 reads ₹21 and not ₹21.00", async () => {
  // 0.07 × 300 is 21.000000000000004 in floating point, and money() prints paise on anything
  // that is not whole. (Tomato, since T-288 types cardamom in Kg at a per-Kg price.)
  const box = await openWithVendor();
  fireEvent.change(box, { target: { value: "tom" } });
  fireEvent.keyDown(box, { key: "Enter" });
  fireEvent.change(screen.getByLabelText("Expected price of Tomato, ripe"), { target: { value: "0.07" } });
  fireEvent.change(screen.getByLabelText("Quantity of Tomato, ripe"), { target: { value: "300" } });
  expect(within(screen.getByLabelText("Quantity of Tomato, ripe").closest("tr")!).getByText("₹21")).toBeInTheDocument();
  expect(orderTotal()).toBe("₹21");
});

describe("creating it", () => {
  it("sends a catalogue line and a one-off line, with the prices as they stand", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "tom" } });
    fireEvent.keyDown(box, { key: "Enter" });
    fireEvent.change(screen.getByLabelText("Quantity of Tomato, ripe"), { target: { value: "9" } });

    fireEvent.change(box, { target: { value: "Plastic stool" } });
    fireEvent.keyDown(box, { key: "Enter" });
    fireEvent.change(screen.getByLabelText("Quantity of Plastic stool"), { target: { value: "4" } });
    fireEvent.change(screen.getByLabelText("Expected price of Plastic stool"), { target: { value: "249.5" } });

    fireEvent.change(box, { target: { value: "card" } });
    fireEvent.keyDown(box, { key: "Enter" });
    fireEvent.change(screen.getByLabelText("Quantity of Cardamom"), { target: { value: "0.5" } });
    fireEvent.change(screen.getByLabelText("Expected price of Cardamom"), { target: { value: "72.5" } });

    fireEvent.change(screen.getByLabelText("Needed by"), { target: { value: tomorrow() } });
    fireEvent.change(screen.getByLabelText("Note for the vendor"), { target: { value: "  Back gate, before 7  " } });

    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });

    expect(createPurchaseOrder).toHaveBeenCalledTimes(1);
    const sent = createPurchaseOrder.mock.calls[0][0];
    expect(sent).toEqual({
      vendorId: "v1",
      neededBy: tomorrow(),
      deliveryLocation: null,
      notes: "Back gate, before 7",
      lines: [
        { ingredientId: "tom", description: null, quantity: 9, unit: "KG", expectedPrice: 32 },
        { ingredientId: null, description: "Plastic stool", quantity: 4, unit: "PIECES", expectedPrice: 249.5 },
        // Cardamom is kept in grams, typed and sent in Kg as the shopping list sends it (T-288):
        // 0.5 Kg at ₹72.50 a Kg is 500 gm of stock.
        { ingredientId: "car", description: null, quantity: 0.5, unit: "KG", expectedPrice: 72.5 },
      ],
    });
    expect(pushMock).toHaveBeenCalledWith("/orders?added=Kalasipalya%20Vegetable%20Mandi&po=po-new");
  });

  it("sends a blank price as null, so the server can fill in the list price", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "cash" } });
    fireEvent.keyDown(box, { key: "Enter" });
    fireEvent.change(screen.getByLabelText("Quantity of Cashew, whole"), { target: { value: "2" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });
    expect(createPurchaseOrder.mock.calls[0][0].lines).toEqual([
      { ingredientId: "cas", description: null, quantity: 2, unit: "KG", expectedPrice: null },
    ]);
  });

  it("names the vendor as required when none is chosen", async () => {
    render(<NewPurchaseOrderPage />);
    await screen.findByRole("combobox", { name: "Vendor" });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });
    expect(screen.getByText("Vendor is required")).toBeInTheDocument();
    expect(createPurchaseOrder).not.toHaveBeenCalled();
  });

  it("names an empty quantity beside its box", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "tom" } });
    fireEvent.keyDown(box, { key: "Enter" });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });
    expect(screen.getByText("Quantity of Tomato, ripe is required")).toBeInTheDocument();
    expect(createPurchaseOrder).not.toHaveBeenCalled();
  });

  it("will not create an order with no items", async () => {
    await openWithVendor();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });
    expect(screen.getByRole("alert")).toHaveTextContent(/at least one item/i);
    expect(createPurchaseOrder).not.toHaveBeenCalled();
  });
});

/**
 * The screen's own refusals say their one sentence and nothing else (T-298, VERIFY2-B N-2).
 *
 * <p>They went through `toApiError(null, …)`, the wrapper for a request that never reached the
 * server, so an order with no items read "An order needs at least one item…" / "Check your
 * connection and try again." / "If you need help, quote KMS-0000". Nothing was sent, the connection
 * was fine, and there is no code for anybody to look up.
 */
describe("the screen's own refusals (T-298, N-2)", () => {
  const onlyAlert = () => {
    const alerts = screen.getAllByRole("alert");
    expect(alerts).toHaveLength(1);
    return alerts[0];
  };

  it("an order with no items says only that, with no connection advice and no code", async () => {
    await openWithVendor();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });
    expect(onlyAlert().textContent).toBe("An order needs at least one item. Type one into the Items table.");
    expect(document.body.textContent).not.toMatch(/connection|KMS-|quote/i);
    expect(createPurchaseOrder).not.toHaveBeenCalled();
  });

  it("Enter on the Vendor select gives the same one sentence", async () => {
    await openWithVendor();
    const form = screen.getByRole("form", { name: "Create a purchase order" });
    await act(async () => {
      fireEvent.submit(form);
    });
    expect(onlyAlert().textContent).toBe("An order needs at least one item. Type one into the Items table.");
    expect(document.body.textContent).not.toMatch(/connection|KMS-/);
  });

  it("goes as soon as an item is added", async () => {
    const box = await openWithVendor();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });
    expect(onlyAlert()).toBeInTheDocument();
    fireEvent.change(box, { target: { value: "tom" } });
    fireEvent.keyDown(box, { key: "Enter" });
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("a needed-by date in the past says only that, with no connection advice and no code", async () => {
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "tom" } });
    fireEvent.keyDown(box, { key: "Enter" });
    fireEvent.change(screen.getByLabelText("Quantity of Tomato, ripe"), { target: { value: "2" } });
    // The box's `min` refuses yesterday first; the screen's own check is for anything the box lets
    // through, so the attribute is lifted to reach it.
    const date = screen.getByLabelText("Needed by");
    date.removeAttribute("min");
    fireEvent.change(date, { target: { value: "2020-01-01" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });
    expect(onlyAlert().textContent).toBe("That date has already passed. Choose today or a day after it.");
    expect(document.body.textContent).not.toMatch(/connection|KMS-/);
    expect(createPurchaseOrder).not.toHaveBeenCalled();
  });
});

/**
 * A refusal is brought into view and given focus (T-304).
 *
 * <p>Create order sits in the sticky header and the notice at the top of the form, so at 390 somebody
 * who had scrolled down to the items pressed it and saw nothing change: the refusal was above the
 * viewport. jsdom has no layout; what is asserted is that the notice is the thing scrolled to and
 * focused, each time Create order is pressed. Positions measured in a browser: `docs/work/proof/T-304.md`.
 */
describe("a refusal is shown where the person is (T-304)", () => {
  const scrolled: Element[] = [];
  beforeEach(() => {
    scrolled.length = 0;
    // The type-ahead scrolls its own options into view too; only a notice counts here.
    Element.prototype.scrollIntoView = function (this: Element) {
      if (this.getAttribute("role") === "alert") scrolled.push(this);
    } as Element["scrollIntoView"];
  });

  it("scrolls the refusal into view and focuses it, and does it again on a second press", async () => {
    await openWithVendor();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });
    const alert = screen.getByRole("alert");
    await waitFor(() => expect(alert).toHaveFocus());
    // Every scroll is to the notice. (A frame still pending from the test before this one can find
    // the same notice once more, so the count is not asserted.)
    expect(scrolled.length).toBeGreaterThan(0);
    expect(scrolled.every((el) => el === alert)).toBe(true);
    expect(alert.getAttribute("tabindex")).toBe("-1");
    const before = scrolled.length;

    // Focus goes elsewhere, the same refusal comes back: shown again.
    screen.getByRole("button", { name: "Create order" }).focus();
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });
    await waitFor(() => expect(screen.getByRole("alert")).toHaveFocus());
    expect(scrolled.length).toBeGreaterThan(before);
  });

  it("does the same for a failure from the server", async () => {
    createPurchaseOrder.mockRejectedValueOnce(new Error("offline"));
    const box = await openWithVendor();
    fireEvent.change(box, { target: { value: "tom" } });
    fireEvent.keyDown(box, { key: "Enter" });
    fireEvent.change(screen.getByLabelText("Quantity of Tomato, ripe"), { target: { value: "2" } });
    await act(async () => {
      fireEvent.click(screen.getByRole("button", { name: "Create order" }));
    });
    const alert = await screen.findByRole("alert");
    await waitFor(() => expect(alert).toHaveFocus());
    expect(scrolled.length).toBeGreaterThan(0);
    expect(scrolled.every((el) => el === alert)).toBe(true);
  });
});
