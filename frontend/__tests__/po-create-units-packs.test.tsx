import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { IngredientView, PackSizeView, VendorDetailView, VendorSupplyView, VendorView } from "@/lib/api";

/**
 * "Create a purchase order" in readable units and in packs (T-288; VERIFY-B D-2, D-3, D-8).
 *
 * <p>D-2: typing 3 for curry leaves ordered 3 gm while the list beside it said ₹60/Kg, and the price
 * box was prefilled 0.06. Now a gm-stocked item is typed in Kg and priced per Kg, and goes to the
 * server as the shopping list sends it (3 KG at ₹60 — 3,000 gm of stock; the conductor's ruling).
 * D-3: the form offered no packs. Now an ingredient's pack sizes are in the Unit select, the vendor's
 * own pack is chosen first, and a pack line is sent with packSizeId and packCount (T-260, T-264).
 * D-8: a rate reads per the readable unit — "₹1,500 / bag · ₹60 / Kg", "₹250 / 500 gm · ₹500 / Kg".
 *
 * <p>The harness is po-create-form.test.tsx's: `useAuthedQuery` is real, the API is mocked.
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

function pack(id: string, name: string | null, quantity: number, unit: string, baseQuantity: number): PackSizeView {
  const size = `${quantity} ${unit === "KG" ? "Kg" : unit === "GM" ? "gm" : unit === "L" ? "L" : unit === "ML" ? "ml" : unit}`;
  return { id, name, quantity, unit, baseQuantity, label: name ? `${name} = ${size}` : size };
}

const INGREDIENTS: IngredientView[] = [
  ingredient("cur", "Curry leaves", "GM"),
  ingredient("mlk", "Milk", "ML"),
  { ...ingredient("ric", "VERIFY-B Rice", "KG"), packSizes: [pack("ps-bag", "Bag", 25, "KG", 25)] },
  { ...ingredient("dal", "Toor dal", "KG"), packSizes: [pack("ps-dal", "Bag", 30, "KG", 30)] },
  {
    ...ingredient("tea", "VERIFY-B Tea", "GM"),
    packSizes: [pack("ps-500", null, 500, "GM", 500), pack("ps-1kg", "Pack", 1, "KG", 1000)],
  },
  { ...ingredient("jag", "Jaggery", "KG"), packSizes: [pack("ps-jag", "Block", 10, "KG", 10)] },
  ingredient("egg", "Coconut", "PIECES"),
];

function packSupply(
  ingredientId: string, ingredientName: string, unit: string, lastPrice: number | null,
  packSizeId: string, packLabel: string, pricePerPack: number | null
): VendorSupplyView {
  return { ...supply(ingredientId, ingredientName, unit, lastPrice), packSizeId, packLabel, pricePerPack };
}

// Curry leaves kept per gram at ₹0.06 — ₹60 a Kg, the VERIFY-B repro. Rice sold as Bag = 25 Kg at
// ₹1,500 a bag. Dal in bags with no pack price quoted, so it is worked out from ₹58 a Kg. Tea per
// gram at ₹0.50, with no vendor pack. Milk per millilitre.
const DETAIL: VendorDetailView = {
  vendor: vendor(),
  supplies: [
    supply("cur", "Curry leaves", "GM", 0.06),
    supply("mlk", "Milk", "ML", 0.05),
    packSupply("ric", "VERIFY-B Rice", "KG", 60, "ps-bag", "Bag (25 Kg)", 1500),
    packSupply("dal", "Toor dal", "KG", 58, "ps-dal", "Bag (30 Kg)", null),
    supply("tea", "VERIFY-B Tea", "GM", 0.5),
    supply("egg", "Coconut", "PIECES", 25),
  ],
  statusHistory: [],
};

beforeEach(() => {
  paramsRef.current = new URLSearchParams();
  pushMock.mockReset();
  listVendors.mockReset().mockResolvedValue([vendor()]);
  listIngredients.mockReset().mockResolvedValue(INGREDIENTS);
  getVendor.mockReset().mockResolvedValue(DETAIL);
  createPurchaseOrder.mockReset().mockResolvedValue({ id: "po-new", poNumber: "PO-2026-0060" });
});

const listed = () => within(screen.getByRole("listbox")).getAllByRole("option").map((o) => o.textContent);
const orderTotal = () => screen.getByTestId("order-total").textContent;

/** Opens the form with Kalasipalya chosen, once both its items and the catalogue are offered. */
async function open() {
  render(<NewPurchaseOrderPage />);
  const picker = await screen.findByRole("combobox", { name: "Vendor" });
  await waitFor(() => expect(within(picker).getAllByRole("option")).toHaveLength(2));
  fireEvent.change(picker, { target: { value: "v1" } });
  await waitFor(() => expect(getVendor).toHaveBeenCalledWith("v1", "test-token"));
  const box = screen.getByRole("combobox", { name: "Add an item" });
  fireEvent.change(box, { target: { value: "jag" } });
  // Jaggery is not the vendor's: it is offered only once the catalogue has arrived too.
  await waitFor(() => expect(listed()).toEqual(["Jaggery · Kg"]));
  fireEvent.change(box, { target: { value: "" } });
  return box;
}

async function pick(box: HTMLElement, typed: string) {
  fireEvent.change(box, { target: { value: typed } });
  fireEvent.keyDown(box, { key: "Enter" });
}

async function create() {
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Create order" }));
  });
}

const row = (name: string) => screen.getByLabelText(`Quantity of ${name}`).closest("tr")!;

describe("readable units (D-2)", () => {
  it("orders curry leaves in Kg: 3 is 3 Kg at ₹60 / Kg, prefilled 60, never 0.06 per gm", async () => {
    const box = await open();
    await pick(box, "curry");

    const r = row("Curry leaves");
    expect(within(r).getByText("Kg")).toBeInTheDocument();
    expect(within(r).queryByText("gm")).toBeNull();
    expect(screen.getByLabelText("Expected price of Curry leaves")).toHaveValue(60);
    expect(within(r).getByTestId("rate-note")).toHaveTextContent(/^₹60 \/ Kg$/);

    fireEvent.change(screen.getByLabelText("Quantity of Curry leaves"), { target: { value: "3" } });
    expect(within(r).getByText("₹180")).toBeInTheDocument();
    expect(orderTotal()).toBe("₹180");
    expect(document.body.textContent).not.toMatch(/0\.06|₹0\.18/);

    await create();
    // Sent as the shopping list sends a plain line: 3 KG at ₹60 a Kg. The ingredient is kept in
    // grams, and the server takes any unit of its family, so stock receives 3,000 gm.
    expect(createPurchaseOrder.mock.calls[0][0].lines).toEqual([
      { ingredientId: "cur", description: null, quantity: 3, unit: "KG", expectedPrice: 60 },
    ]);
  });

  it("reads a part of a Kg as typed: 0.25 beside Kg is a quarter Kg, sent as 0.25 KG", async () => {
    const box = await open();
    await pick(box, "curry");
    fireEvent.change(screen.getByLabelText("Quantity of Curry leaves"), { target: { value: "0.25" } });
    expect(orderTotal()).toBe("₹15");
    await create();
    expect(createPurchaseOrder.mock.calls[0][0].lines[0]).toMatchObject({ quantity: 0.25, unit: "KG", expectedPrice: 60 });
  });

  it("orders milk kept in ml in L, at ₹50 / L", async () => {
    const box = await open();
    await pick(box, "milk");
    const r = row("Milk");
    expect(within(r).getByText("L")).toBeInTheDocument();
    expect(screen.getByLabelText("Expected price of Milk")).toHaveValue(50);
    expect(within(r).getByTestId("rate-note")).toHaveTextContent("₹50 / L");
    fireEvent.change(screen.getByLabelText("Quantity of Milk"), { target: { value: "20" } });
    await create();
    expect(createPurchaseOrder.mock.calls[0][0].lines[0]).toEqual(
      { ingredientId: "mlk", description: null, quantity: 20, unit: "L", expectedPrice: 50 }
    );
  });

  it("leaves a count as it is: coconuts in pieces at ₹25 / piece", async () => {
    const box = await open();
    await pick(box, "coco");
    expect(within(row("Coconut")).getByText("pieces")).toBeInTheDocument();
    expect(within(row("Coconut")).getByTestId("rate-note")).toHaveTextContent("₹25 / piece");
  });

  it("follows a price typed over the prefill, in words under the box", async () => {
    const box = await open();
    await pick(box, "curry");
    fireEvent.change(screen.getByLabelText("Expected price of Curry leaves"), { target: { value: "64.5" } });
    expect(within(row("Curry leaves")).getByTestId("rate-note")).toHaveTextContent("₹64.50 / Kg");
  });
});

describe("packs (D-3, D-8)", () => {
  it("starts rice in the vendor's pack: 4 × Bag (25 Kg) at ₹1,500 / bag · ₹60 / Kg, sent with the pack", async () => {
    const box = await open();
    await pick(box, "rice");

    const unit = screen.getByRole("combobox", { name: "Unit for VERIFY-B Rice" });
    expect(unit).toHaveValue("ps-bag");
    expect(within(unit).getAllByRole("option").map((o) => o.textContent)).toEqual(["Kg", "× Bag (25 Kg)"]);
    const price = screen.getByLabelText("Expected price of VERIFY-B Rice");
    expect(price).toHaveValue(1500);
    expect(within(row("VERIFY-B Rice")).getByTestId("rate-note")).toHaveTextContent(/^₹1,500 \/ bag · ₹60 \/ Kg$/);

    const qty = screen.getByLabelText("Quantity of VERIFY-B Rice");
    expect(qty).toHaveAttribute("step", "1");
    fireEvent.change(qty, { target: { value: "4" } });
    expect(within(row("VERIFY-B Rice")).getByText("₹6,000")).toBeInTheDocument();

    await create();
    // Exactly the shopping list's pack line (T-264): the stock amount in the ingredient's unit, the
    // price per that unit, and the pack with its count (T-260 stores packs × size).
    expect(createPurchaseOrder.mock.calls[0][0].lines).toEqual([
      { ingredientId: "ric", description: null, quantity: 100, unit: "KG", expectedPrice: 60, packSizeId: "ps-bag", packCount: 4 },
    ]);
  });

  it("works a bag's price out from the list price where the vendor quotes none: 30 Kg at ₹58 is ₹1,740", async () => {
    const box = await open();
    await pick(box, "toor");
    expect(screen.getByRole("combobox", { name: "Unit for Toor dal" })).toHaveValue("ps-dal");
    expect(screen.getByLabelText("Expected price of Toor dal")).toHaveValue(1740);
    expect(within(row("Toor dal")).getByTestId("rate-note")).toHaveTextContent("₹1,740 / bag · ₹58 / Kg");
  });

  it("offers a gm item's own packs, and says a 500 gm pack's rate per Kg: ₹250 / 500 gm · ₹500 / Kg", async () => {
    const box = await open();
    await pick(box, "tea");

    // The vendor sells tea by weight, so the line starts plain, in Kg.
    const unit = screen.getByRole("combobox", { name: "Unit for VERIFY-B Tea" });
    expect(unit).toHaveValue("plain");
    expect(within(unit).getAllByRole("option").map((o) => o.textContent)).toEqual(["Kg", "× 500 gm", "× Pack (1 Kg)"]);
    expect(screen.getByLabelText("Expected price of VERIFY-B Tea")).toHaveValue(500);

    fireEvent.change(unit, { target: { value: "ps-500" } });
    // The price keeps its meaning: ₹500 a Kg is ₹250 a 500 gm pack.
    expect(screen.getByLabelText("Expected price of VERIFY-B Tea")).toHaveValue(250);
    const note = within(row("VERIFY-B Tea")).getByTestId("rate-note");
    expect(note).toHaveTextContent(/^₹250 \/ 500 gm · ₹500 \/ Kg$/);
    expect(note.textContent).not.toMatch(/\/ gm$/);

    fireEvent.change(screen.getByLabelText("Quantity of VERIFY-B Tea"), { target: { value: "2" } });
    expect(orderTotal()).toBe("₹500");
    await create();
    expect(createPurchaseOrder.mock.calls[0][0].lines).toEqual([
      { ingredientId: "tea", description: null, quantity: 1000, unit: "GM", expectedPrice: 0.5, packSizeId: "ps-500", packCount: 2 },
    ]);
  });

  it("moves a typed price between a bag and a Kg without changing what it means", async () => {
    const box = await open();
    await pick(box, "rice");
    const unit = screen.getByRole("combobox", { name: "Unit for VERIFY-B Rice" });
    const price = screen.getByLabelText("Expected price of VERIFY-B Rice");
    fireEvent.change(price, { target: { value: "1450" } });

    fireEvent.change(unit, { target: { value: "plain" } });
    expect(price).toHaveValue(58);
    expect(screen.getByLabelText("Quantity of VERIFY-B Rice")).toHaveAttribute("step", "any");
    expect(within(row("VERIFY-B Rice")).getByTestId("rate-note")).toHaveTextContent(/^₹58 \/ Kg$/);

    fireEvent.change(unit, { target: { value: "ps-bag" } });
    expect(price).toHaveValue(1450);
  });

  it("sends a plain amount of a packed ingredient without pack keys", async () => {
    const box = await open();
    await pick(box, "rice");
    fireEvent.change(screen.getByRole("combobox", { name: "Unit for VERIFY-B Rice" }), { target: { value: "plain" } });
    fireEvent.change(screen.getByLabelText("Quantity of VERIFY-B Rice"), { target: { value: "12.5" } });
    await create();
    expect(createPurchaseOrder.mock.calls[0][0].lines).toEqual([
      { ingredientId: "ric", description: null, quantity: 12.5, unit: "KG", expectedPrice: 60 },
    ]);
  });

  it("refuses part of a bag beside the box, and sends nothing", async () => {
    const box = await open();
    await pick(box, "rice");
    fireEvent.change(screen.getByLabelText("Quantity of VERIFY-B Rice"), { target: { value: "2.5" } });
    await create();
    expect(createPurchaseOrder).not.toHaveBeenCalled();
    // A pack line is whole because a vendor does not sell a third of a bag, not because of the
    // unit — the rice inside is weighed. So the refusal says packs (T-431).
    expect(screen.getAllByText("VERIFY-B Rice is ordered in whole packs").length).toBeGreaterThan(0);
  });

  it("offers an ingredient the vendor does not sell in its own packs, starting plain with no price", async () => {
    const box = await open();
    await pick(box, "jag");
    const unit = screen.getByRole("combobox", { name: "Unit for Jaggery" });
    expect(unit).toHaveValue("plain");
    expect(within(unit).getAllByRole("option").map((o) => o.textContent)).toEqual(["Kg", "× Block (10 Kg)"]);
    expect(screen.getByLabelText("Expected price of Jaggery")).toHaveValue(null);
    expect(within(row("Jaggery")).queryByTestId("rate-note")).toBeNull();
    fireEvent.change(unit, { target: { value: "ps-jag" } });
    fireEvent.change(screen.getByLabelText("Quantity of Jaggery"), { target: { value: "3" } });
    await create();
    expect(createPurchaseOrder.mock.calls[0][0].lines).toEqual([
      { ingredientId: "jag", description: null, quantity: 30, unit: "KG", expectedPrice: null, packSizeId: "ps-jag", packCount: 3 },
    ]);
  });
});

it("one order with a gm item in Kg and a pack line totals both: 3 Kg curry leaves + 4 bags of rice", async () => {
  const box = await open();
  await pick(box, "curry");
  fireEvent.change(screen.getByLabelText("Quantity of Curry leaves"), { target: { value: "3" } });
  await pick(screen.getByRole("combobox", { name: "Add an item" }), "rice");
  fireEvent.change(screen.getByLabelText("Quantity of VERIFY-B Rice"), { target: { value: "4" } });
  expect(orderTotal()).toBe("₹6,180");
  expect(screen.getByText("2 items")).toBeInTheDocument();
  await create();
  expect(createPurchaseOrder.mock.calls[0][0].lines).toEqual([
    { ingredientId: "cur", description: null, quantity: 3, unit: "KG", expectedPrice: 60 },
    { ingredientId: "ric", description: null, quantity: 100, unit: "KG", expectedPrice: 60, packSizeId: "ps-bag", packCount: 4 },
  ]);
});

it("lets the type-ahead span the empty Item, Quantity and Unit cells, so it does not set the Item column's width", async () => {
  // Measured at 1024 on the running app (T-288): with the 256px type-ahead inside the Item cell,
  // a line in packs made the table 891px wide in a 680px page. Spanning the cells beside it,
  // which were always empty, is what let the table fit.
  await open();
  const cell = screen.getByRole("combobox", { name: "Add an item" }).closest("td")!;
  expect(cell).toHaveAttribute("colspan", "3");
  expect(cell.closest("tr")!.children).toHaveLength(4);
});

/**
 * The type-ahead names the unit the line is created in (T-298, VERIFY2-B N-1).
 *
 * <p>It said the ingredient's stock unit where there was no list price: "VERIFY2-B Jeera · gm" and
 * Kalasipalya's "Curry leaves · gm", each of which, picked, became a Kg line.
 */
describe("the option says the unit the line is created in (T-298, N-1)", () => {
  const UNPRICED: VendorDetailView = {
    vendor: vendor(),
    supplies: [
      supply("cur", "Curry leaves", "GM", null),
      supply("mlk", "Milk", "ML", null),
      supply("egg", "Coconut", "PIECES", null),
      packSupply("ric", "VERIFY-B Rice", "KG", null, "ps-bag", "Bag (25 Kg)", null),
      packSupply("tea", "VERIFY-B Tea", "GM", null, "ps-500", "500 gm", null),
    ],
    statusHistory: [],
  };

  it("a vendor's item with no list price: Kg for grams, L for millilitres, the pack for a pack", async () => {
    getVendor.mockResolvedValue(UNPRICED);
    const box = await open();
    const says: Record<string, string> = {};
    for (const typed of ["curry", "milk", "coco", "rice", "tea"]) {
      fireEvent.change(box, { target: { value: typed } });
      says[typed] = listed()[0]!;
    }
    expect(says).toEqual({
      curry: "Curry leaves · Kg",
      milk: "Milk · L",
      coco: "Coconut · pieces",
      rice: "VERIFY-B Rice · Bag (25 Kg)",
      tea: "VERIFY-B Tea · 500 gm",
    });
    fireEvent.change(box, { target: { value: "" } });

    // And each, picked, is that: Kg, L, pieces, × Bag (25 Kg), × 500 gm.
    for (const typed of ["curry", "milk", "coco", "rice", "tea"]) await pick(screen.getByRole("combobox", { name: "Add an item" }), typed);
    expect(within(row("Curry leaves")).getByText("Kg")).toBeInTheDocument();
    expect(within(row("Milk")).getByText("L")).toBeInTheDocument();
    expect(within(row("Coconut")).getByText("pieces")).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Unit for VERIFY-B Rice" })).toHaveValue("ps-bag");
    expect(screen.getByRole("combobox", { name: "Unit for VERIFY-B Tea" })).toHaveValue("ps-500");
  });

  it("an ingredient the vendor does not sell: its readable unit, never gm or ml", async () => {
    getVendor.mockResolvedValue({ ...UNPRICED, supplies: [] });
    const box = await open();
    fireEvent.change(box, { target: { value: "curry" } });
    expect(listed()).toEqual(["Curry leaves · Kg"]);
    fireEvent.change(box, { target: { value: "milk" } });
    expect(listed()).toEqual(["Milk · L"]);
    // Tea has packs but is not this vendor's, so it starts plain, in Kg.
    fireEvent.change(box, { target: { value: "tea" } });
    expect(listed()).toEqual(["VERIFY-B Tea · Kg"]);
    await pick(box, "tea");
    expect(screen.getByRole("combobox", { name: "Unit for VERIFY-B Tea" })).toHaveValue("plain");
    expect(within(screen.getByRole("combobox", { name: "Unit for VERIFY-B Tea" })).getByRole("option", { name: "Kg" })).toBeInTheDocument();
  });
});
