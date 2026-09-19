import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import {
  ApiError,
  type AttachmentView,
  type BillableDeliveryView,
  type IngredientView,
  type VendorView,
} from "@/lib/api";

/**
 * "Create an invoice" (R-INV-1..5, T-273): the deliveries being billed, the items table with its
 * packs and warnings, the direct invoice's type-ahead, the required copy of the bill, the totals
 * check, and exactly what is sent.
 *
 * <p>`useAuthedQuery` is not mocked: the vendor → deliveries re-fetch is its behaviour. The API is,
 * because that is where the network would be.
 */

const h = vi.hoisted(() => ({
  authRef: {
    current: {
      status: "signed-in",
      appUser: {
        userId: "u1",
        fullName: "Gopal Das",
        role: "KITCHEN_MANAGER",
        tenantName: "Bengaluru Temple",
        tenantSlug: "bengaluru",
        temples: [],
      },
      getToken: async () => "test-token",
      refresh: () => {},
      signOut: () => {},
      switchTemple: () => {},
    } as Record<string, unknown>,
  },
  pushMock: vi.fn(),
  listVendors: vi.fn(),
  listIngredients: vi.fn(),
  listBillableDeliveries: vi.fn(),
  getVendor: vi.fn(),
  uploadBill: vi.fn(),
  addPackSize: vi.fn(),
  recordInvoice: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: h.pushMock, replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
  usePathname: () => "/invoices/new",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => h.authRef.current }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      listVendors: h.listVendors,
      listIngredients: h.listIngredients,
      listBillableDeliveries: h.listBillableDeliveries,
      getVendor: h.getVendor,
      uploadBill: h.uploadBill,
      addPackSize: h.addPackSize,
      recordInvoice: h.recordInvoice,
    },
  };
});

import NewInvoicePage from "@/app/invoices/new/page";

const vendor = (id: string, name: string) => ({ id, name, active: true }) as unknown as VendorView;
const ingredient = (id: string, name: string, unit: string, packSizes: IngredientView["packSizes"] = []) =>
  ({ id, name, unit, packSizes, category: "VEGETABLES" }) as unknown as IngredientView;

const KVM = vendor("v1", "Kalasipalya Vegetable Mandi");
const SRI = vendor("v2", "Sri Balaji Traders");

let INGREDIENTS: IngredientView[];

const D44: BillableDeliveryView = {
  receiptId: "r1",
  purchaseOrderId: "po44",
  poNumber: "PO-2026-0044",
  receivedOn: "2026-09-12",
  receivedByName: "Govinda Das",
  lines: [
    { goodsReceiptLineId: "gl-tom", ingredientId: "tom", itemName: "Tomato, ripe", orderedQty: 50, deliveredQty: 45, unit: "KG", packSizeId: null, packLabel: null, packQuantity: null },
    { goodsReceiptLineId: "gl-rice", ingredientId: "rice", itemName: "Sona masoori rice", orderedQty: 100, deliveredQty: 100, unit: "KG", packSizeId: "bag25", packLabel: "Bag (25 Kg)", packQuantity: 25 },
  ],
};
const D45: BillableDeliveryView = {
  receiptId: "r2",
  purchaseOrderId: "po45",
  poNumber: "PO-2026-0045",
  receivedOn: "2026-09-14",
  receivedByName: null,
  lines: [
    { goodsReceiptLineId: "gl-crd", ingredientId: "crd", itemName: "Cardamom", orderedQty: 500, deliveredQty: 500, unit: "GM", packSizeId: null, packLabel: null, packQuantity: null },
  ],
};
const D99: BillableDeliveryView = { ...D45, receiptId: "r9", poNumber: "PO-2026-0099", lines: [] };

const BILL: AttachmentView = {
  id: "att-1",
  kind: "INVOICE_BILL",
  contentType: "image/jpeg",
  sizeBytes: 4,
  originalName: "KVM-0917.jpg",
  uploadedAt: "2026-09-19T05:30:00Z",
};

beforeEach(() => {
  INGREDIENTS = [
    ingredient("tom", "Tomato, ripe", "KG"),
    ingredient("rice", "Sona masoori rice", "KG", [
      { id: "bag25", name: "Bag", quantity: 25, unit: "KG", baseQuantity: 25, label: "Bag = 25 Kg" },
    ]),
    ingredient("crd", "Cardamom", "GM"),
    ingredient("toor", "Toor dal", "KG"),
  ];
  h.listVendors.mockReset().mockResolvedValue([KVM, SRI]);
  h.listIngredients.mockReset().mockImplementation(async () => INGREDIENTS);
  h.listBillableDeliveries
    .mockReset()
    .mockImplementation(async (id: string) => (id === "v1" ? [D44, D45] : id === "v2" ? [D99] : []));
  h.getVendor.mockReset().mockResolvedValue({ vendor: KVM, supplies: [], statusHistory: [] });
  h.uploadBill.mockReset().mockResolvedValue(BILL);
  h.addPackSize.mockReset();
  h.recordInvoice.mockReset().mockResolvedValue({ invoice: { id: "inv9" }, duplicateWarning: false });
  h.pushMock.mockReset();
  URL.createObjectURL = vi.fn(() => "blob:t273");
  URL.revokeObjectURL = vi.fn();
});

afterEach(() => vi.restoreAllMocks());

const box = (label: string) => screen.getByLabelText(label) as HTMLInputElement;
const type = (el: Element, value: string) => fireEvent.change(el, { target: { value } });

async function open() {
  render(<NewInvoicePage />);
  await screen.findByRole("option", { name: KVM.name });
  await waitFor(() => expect(h.listIngredients).toHaveBeenCalled());
}

async function chooseVendor(id = "v1") {
  type(document.querySelector('select[name="vendorId"]')!, id);
  await waitFor(() => expect(h.listBillableDeliveries).toHaveBeenCalledWith(id, "test-token"));
}

async function tick(label: string) {
  fireEvent.click(await screen.findByRole("checkbox", { name: label }));
}

function header() {
  type(document.querySelector('input[name="invoiceNumber"]')!, "KVM/2026/0917");
  type(document.querySelector('input[name="invoiceDate"]')!, "2026-09-17");
}

async function upload() {
  const file = new File([new Uint8Array([0xff, 0xd8, 0xff, 0xe0])], "KVM-0917.jpg", { type: "image/jpeg" });
  fireEvent.change(document.querySelector('input[type="file"]')!, { target: { files: [file] } });
  await screen.findByText("Photo");
}

function totals(grand: string, gst = "", charges = "", discount = "") {
  type(document.querySelector('input[name="gst"]')!, gst);
  type(document.querySelector('input[name="otherCharges"]')!, charges);
  type(document.querySelector('input[name="discount"]')!, discount);
  type(document.querySelector('input[name="grandTotal"]')!, grand);
}

const save = () => fireEvent.click(screen.getByRole("button", { name: "Save invoice" }));

// R-INV-3's words, then what came (T-290): two items on 0044, the one line of cardamom on 0045.
const L44 = "PO-2026-0044 · delivered 12 Sept · received by Govinda Das · 2 items";
const L45 = "PO-2026-0045 · delivered 14 Sept · 500 gm";

describe("Create an invoice (R-INV-1)", () => {
  it("is the form itself, titled Create an invoice, with Cancel and Save invoice", async () => {
    await open();
    expect(screen.getByRole("heading", { level: 1, name: "Create an invoice" })).toBeInTheDocument();
    expect(screen.getByRole("form", { name: "Create an invoice" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/invoices");
    expect(screen.getByRole("button", { name: "Save invoice" })).toBeInTheDocument();
    // The old text reference is gone: the bill is an upload now (R-INV-2).
    expect(screen.queryByText("Scan reference")).not.toBeInTheDocument();
  });
});

describe("the deliveries being billed (R-INV-3)", () => {
  it("pulls in the lines of both deliveries picked, locked: nothing to add and nothing to remove", async () => {
    await open();
    await chooseVendor();
    await tick(L44);
    await tick(L45);

    const table = screen.getByRole("table");
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((r) => within(r).getAllByRole("cell")[0].textContent)).toEqual([
      "Tomato, ripe",
      "Sona masoori rice",
      "Cardamom",
    ]);
    expect(within(table).queryByRole("combobox", { name: "Add an item" })).not.toBeInTheDocument();
    expect(within(table).queryByRole("button", { name: /remove/i })).not.toBeInTheDocument();
    expect(screen.getByText(/Pulled in from PO-2026-0044 and PO-2026-0045\./)).toBeInTheDocument();
    expect(within(table).getAllByRole("columnheader").map((c) => c.textContent)).toEqual([
      "Item",
      "Ordered",
      "Delivered",
      "Billed qty",
      "Amount (₹)",
      "Rate",
    ]);
  });

  it("drops a delivery's lines when it is unticked", async () => {
    await open();
    await chooseVendor();
    await tick(L44);
    await tick(L45);
    await tick(L44);
    const rows = within(screen.getByRole("table")).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent("Cardamom");
  });
});

describe("the items table (R-INV-4)", () => {
  it("bills what was delivered unless changed, and an item not billed stays at 0", async () => {
    await open();
    await chooseVendor();
    await tick(L44);
    await tick(L45);
    expect(box("Tomato, ripe billed quantity").value).toBe("45");
    expect((screen.getByLabelText("Tomato, ripe billed in") as HTMLSelectElement).value).toBe("KG");
    expect(box("Cardamom billed quantity").value).toBe("500");
    type(box("Cardamom billed quantity"), "0");
    // Nothing billed, so no amount is asked for.
    expect(box("Cardamom amount")).not.toBeRequired();
    expect(box("Tomato, ripe amount")).toBeRequired();
  });

  it("warns in amber when more is billed than was delivered, in exactly the document's words", async () => {
    await open();
    await chooseVendor();
    await tick(L44);
    type(box("Tomato, ripe billed quantity"), "50");
    const warning = screen.getByText("Billed 50 Kg, 45 Kg delivered");
    expect(warning).toHaveClass("text-warning");
    type(box("Tomato, ripe billed quantity"), "45");
    expect(screen.queryByText(/delivered$/)).not.toBeInTheDocument();
  });

  it("works the rate out as Amount ÷ Billed qty", async () => {
    await open();
    await chooseVendor();
    await tick(L44);
    const row = screen.getByText("Tomato, ripe").closest("tr")!;
    expect(within(row).getByText("—")).toBeInTheDocument();
    type(box("Tomato, ripe amount"), "4500");
    expect(within(row).getByText("₹100 / Kg")).toBeInTheDocument();
    // Billed in grams, the same price still reads per Kg (T-293): one form on every screen, never
    // "₹0.10 / gm".
    fireEvent.change(screen.getByLabelText("Tomato, ripe billed in"), { target: { value: "GM" } });
    expect(box("Tomato, ripe billed quantity").value).toBe("45000");
    expect(within(row).getByText("₹100 / Kg")).toBeInTheDocument();
    expect(row.textContent).not.toMatch(/\/ ?gm\b/);
  });

  it("bills a pack line in the order's pack, and says its rate per pack and per Kg", async () => {
    await open();
    await chooseVendor();
    await tick(L44);
    const row = screen.getByText("Sona masoori rice").closest("tr")!;
    // Ordered and Delivered as the order said them, with the stock under (conductor's ruling).
    expect(within(row).getAllByText("4 × Bag (25 Kg)")).toHaveLength(2);
    expect(within(row).getAllByText("100 Kg").length).toBeGreaterThanOrEqual(2);
    expect(box("Sona masoori rice billed quantity").value).toBe("4");
    const unit = screen.getByLabelText("Sona masoori rice billed in") as HTMLSelectElement;
    expect(unit.selectedOptions[0].textContent).toBe("× Bag (25 Kg)");
    type(box("Sona masoori rice amount"), "6000");
    expect(within(row).getByText("₹1,500 / bag · ₹60 / Kg")).toBeInTheDocument();
  });

  // T-302, C-2 as T-299 fixed it on Deliveries: one pack with no name is its own size, so "500 gm"
  // under "1 × 500 gm" is dropped. Counted in packs, not by comparing text ("0.5 Kg" over 500 gm is a
  // repeat too); two packs keep their total, and a named pack keeps its size.
  it("says one unnamed pack once in Ordered and Delivered, keeping the amount under two packs and a named pack (T-302)", async () => {
    const gl = (id: string, itemName: string, qty: number, unit: string, packLabel: string, packQuantity: number) =>
      ({ goodsReceiptLineId: `gl-${id}`, ingredientId: id, itemName, orderedQty: qty, deliveredQty: qty, unit, packSizeId: `p-${id}`, packLabel, packQuantity });
    const D46: BillableDeliveryView = {
      receiptId: "r46",
      purchaseOrderId: "po46",
      poNumber: "PO-2026-0046",
      receivedOn: "2026-09-15",
      receivedByName: null,
      lines: [
        gl("tea", "Tea", 500, "GM", "500 gm", 500),
        gl("ghee", "Ghee", 500, "GM", "0.5 Kg", 500),
        gl("elc", "Elaichi", 1000, "GM", "500 gm", 500),
        gl("sona", "Sona rice", 25, "KG", "Bag (25 Kg)", 25),
      ],
    };
    h.listBillableDeliveries.mockReset().mockImplementation(async () => [D46]);
    await open();
    await chooseVendor();
    fireEvent.click(await screen.findByRole("checkbox", { name: /^PO-2026-0046/ }));
    const shown = (name: string) =>
      within(screen.getByText(name).closest("tr")!).getAllByRole("cell").slice(1, 3).map((c) => c.textContent);
    expect(shown("Tea")).toEqual(["1 × 500 gm", "1 × 500 gm"]);
    expect(shown("Ghee")).toEqual(["1 × 0.5 Kg", "1 × 0.5 Kg"]);
    expect(shown("Elaichi")).toEqual(["2 × 500 gm1 Kg", "2 × 500 gm1 Kg"]);
    expect(shown("Sona rice")).toEqual(["1 × Bag (25 Kg)25 Kg", "1 × Bag (25 Kg)25 Kg"]);
  });

  // T-303: the same rule for the stock readout under the Billed qty box, through the one helper
  // (`repeatsPack`). Box "1" and "× 500 gm" already say 500 gm, so "500 gm" under them went; it
  // comes back the moment the box says anything but one pack.
  it("says one unnamed pack once under Billed qty too, keeping the amount for two packs and a named pack (T-303)", async () => {
    const gl = (id: string, itemName: string, qty: number, unit: string, packLabel: string, packQuantity: number) =>
      ({ goodsReceiptLineId: `gl-${id}`, ingredientId: id, itemName, orderedQty: qty, deliveredQty: qty, unit, packSizeId: `p-${id}`, packLabel, packQuantity });
    const D47: BillableDeliveryView = {
      receiptId: "r47",
      purchaseOrderId: "po47",
      poNumber: "PO-2026-0047",
      receivedOn: "2026-09-15",
      receivedByName: null,
      lines: [
        gl("tea", "Tea", 500, "GM", "500 gm", 500),
        gl("ghee", "Ghee", 500, "GM", "0.5 Kg", 500),
        gl("elc", "Elaichi", 1000, "GM", "500 gm", 500),
        gl("sona", "Sona rice", 25, "KG", "Bag (25 Kg)", 25),
      ],
    };
    h.listBillableDeliveries.mockReset().mockImplementation(async () => [D47]);
    await open();
    await chooseVendor();
    fireEvent.click(await screen.findByRole("checkbox", { name: /^PO-2026-0047/ }));
    const readout = (name: string) =>
      box(`${name} billed quantity`).closest("td")!.querySelector(".pl-field-inset")?.textContent ?? null;
    expect(box("Tea billed quantity")).toHaveValue(1);
    expect(readout("Tea")).toBeNull();
    expect(readout("Ghee")).toBeNull();
    expect(readout("Elaichi")).toBe("1 Kg");
    expect(readout("Sona rice")).toBe("25 Kg");
    // Two packs of it is new information again.
    type(box("Tea billed quantity"), "2");
    expect(readout("Tea")).toBe("1 Kg");
  });

  // T-290, VERIFY-D's D2. jsdom lays nothing out, so this pins the rule the measurement settled: on
  // a phone card Billed qty sits beside Amount as in the mock (box + "Kg" is 149px in a 171px half),
  // and only a line billed in a pack, whose "× Bag (25 Kg)" list makes 210px, takes the full width.
  it("puts Billed qty beside Amount on a phone card, spanning the card only for a line billed in a pack", async () => {
    await open();
    await chooseVendor();
    await tick(L44);
    const cell = (name: string) => box(`${name} billed quantity`).closest("td")!;
    expect(cell("Tomato, ripe").className).not.toContain("col-span-2");
    expect(cell("Sona masoori rice").className).toContain("max-lg:col-span-2");
    // Switched out of the pack, the rice line goes back beside its Amount.
    type(box("Sona masoori rice billed in"), "KG");
    expect(cell("Sona masoori rice").className).not.toContain("col-span-2");
  });

  it("defines a new pack on the ingredient on the spot, then bills the line in it", async () => {
    h.addPackSize.mockImplementation(async () => {
      INGREDIENTS = INGREDIENTS.map((i) =>
        i.id === "tom"
          ? { ...i, packSizes: [{ id: "crate20", name: "Crate", quantity: 20, unit: "KG", baseQuantity: 20, label: "Crate = 20 Kg" }] }
          : i,
      );
      return { id: "crate20" };
    });
    await open();
    await chooseVendor();
    await tick(L44);
    type(screen.getByLabelText("Tomato, ripe billed in"), "__add_pack__");

    const fields = screen.getByRole("group", { name: "New pack size for Tomato, ripe" });
    type(within(fields).getByLabelText("Pack name (optional)"), "Crate");
    type(within(fields).getByLabelText("Size"), "20");
    fireEvent.click(within(fields).getByRole("button", { name: "Add pack size" }));

    await waitFor(() => expect(h.addPackSize).toHaveBeenCalledWith("tom", { name: "Crate", quantity: 20, unit: "KG" }, "test-token"));
    await waitFor(() =>
      expect((screen.getByLabelText("Tomato, ripe billed in") as HTMLSelectElement).value).toBe("pack:crate20"),
    );
    // 45 Kg is 2.25 crates: the box keeps the amount it stood for.
    expect(box("Tomato, ripe billed quantity").value).toBe("2.25");
    expect(screen.queryByRole("group", { name: "New pack size for Tomato, ripe" })).not.toBeInTheDocument();
  });
});

describe("a direct invoice (R-INV-3)", () => {
  it("adds items with the purchase order's type-ahead, one-off items included, and sends no deliveries", async () => {
    await open();
    await chooseVendor();
    fireEvent.click(screen.getByLabelText("Direct, with no purchase order"));
    expect(screen.queryByText(L44)).not.toBeInTheDocument();
    // Optional now: the lines say what was bought (conductor's ruling).
    expect(document.querySelector('input[name="description"]')).not.toBeRequired();

    const add = screen.getByRole("combobox", { name: "Add an item" });
    type(add, "Toor");
    fireEvent.mouseDown(await screen.findByRole("option", { name: /Toor dal/ }));
    type(screen.getByRole("combobox", { name: "Add an item" }), "Plastic stool");
    fireEvent.mouseDown(await screen.findByRole("option", { name: "Add ‘Plastic stool’ as a one-off item" }));

    expect(screen.getByText("One-off item")).toBeInTheDocument();
    type(box("Toor dal billed quantity"), "10");
    type(box("Toor dal amount"), "1200");
    type(box("Plastic stool billed quantity"), "2");
    type(box("Plastic stool amount"), "500");

    header();
    await upload();
    totals("1700");
    save();

    await waitFor(() => expect(h.recordInvoice).toHaveBeenCalledTimes(1));
    const sent = h.recordInvoice.mock.calls[0][0];
    expect(sent.receiptIds).toEqual([]);
    expect(sent.lines).toEqual([
      { goodsReceiptLineId: null, ingredientId: "toor", description: null, billedQty: 10, unit: "KG", packSizeId: null, packCount: null, amount: 1200 },
      { goodsReceiptLineId: null, ingredientId: null, description: "Plastic stool", billedQty: 2, unit: "PIECES", packSizeId: null, packCount: null, amount: 500 },
    ]);
  });

  it("lets a direct line be removed", async () => {
    await open();
    await chooseVendor();
    fireEvent.click(screen.getByLabelText("Direct, with no purchase order"));
    type(screen.getByRole("combobox", { name: "Add an item" }), "Toor");
    fireEvent.mouseDown(await screen.findByRole("option", { name: /Toor dal/ }));
    fireEvent.click(screen.getByRole("button", { name: "Remove Toor dal" }));
    expect(screen.queryByLabelText("Toor dal billed quantity")).not.toBeInTheDocument();
  });
});

describe("saving", () => {
  async function billedPair() {
    await open();
    await chooseVendor();
    await tick(L44);
    await tick(L45);
    type(box("Tomato, ripe billed quantity"), "50");
    type(box("Tomato, ripe amount"), "4500");
    type(box("Sona masoori rice amount"), "6000");
    type(box("Cardamom billed quantity"), "0");
    header();
  }

  it("sends exactly RecordInvoiceInput: every key, no sub total, lines in the stock unit with their pack", async () => {
    await billedPair();
    await upload();
    totals("11045", "525", "50", "30");
    type(document.querySelector('input[name="otherChargesNote"]')!, "Delivery to the temple");
    save();

    await waitFor(() => expect(h.recordInvoice).toHaveBeenCalledTimes(1));
    const [sent, token] = h.recordInvoice.mock.calls[0];
    expect(token).toBe("test-token");
    expect(Object.keys(sent).sort()).toEqual(
      [
        "vendorId",
        "description",
        "invoiceNumber",
        "invoiceDate",
        "dueDate",
        "receiptIds",
        "lines",
        "gstAmount",
        "otherCharges",
        "otherChargesNote",
        "discount",
        "grandTotal",
        "billAttachmentId",
      ].sort(),
    );
    // The absence rule: what the contract dropped must not ride along.
    for (const gone of ["subTotal", "amount", "scanRef", "purchaseOrderId"]) expect(sent).not.toHaveProperty(gone);
    expect(sent).toEqual({
      vendorId: "v1",
      description: null,
      invoiceNumber: "KVM/2026/0917",
      invoiceDate: "2026-09-17",
      dueDate: null,
      receiptIds: ["r1", "r2"],
      lines: [
        { goodsReceiptLineId: "gl-tom", ingredientId: "tom", description: null, billedQty: 50, unit: "KG", packSizeId: null, packCount: null, amount: 4500 },
        { goodsReceiptLineId: "gl-rice", ingredientId: "rice", description: null, billedQty: 100, unit: "KG", packSizeId: "bag25", packCount: 4, amount: 6000 },
        { goodsReceiptLineId: "gl-crd", ingredientId: "crd", description: null, billedQty: 0, unit: "GM", packSizeId: null, packCount: null, amount: 0 },
      ],
      gstAmount: 525,
      otherCharges: 50,
      otherChargesNote: "Delivery to the temple",
      discount: 30,
      grandTotal: 11045,
      billAttachmentId: "att-1",
    });
    for (const line of sent.lines) {
      expect(Object.keys(line).sort()).toEqual(
        ["goodsReceiptLineId", "ingredientId", "description", "billedQty", "unit", "packSizeId", "packCount", "amount"].sort(),
      );
      expect(line).not.toHaveProperty("rate");
    }
  });

  it("goes to the new invoice's page, carrying the number and the duplicate warning", async () => {
    h.recordInvoice.mockResolvedValue({ invoice: { id: "inv9" }, duplicateWarning: true });
    await billedPair();
    await upload();
    totals("10500");
    save();
    await waitFor(() =>
      expect(h.pushMock).toHaveBeenCalledWith("/invoices/inv9?recorded=KVM%2F2026%2F0917&duplicate=1"),
    );
  });

  it("will not save without the copy of the bill, and says so with the standard required message", async () => {
    await billedPair();
    totals("10500");
    save();
    expect(await screen.findByText("Copy of the bill is required")).toBeInTheDocument();
    expect(screen.getByText("Upload a copy of the bill.")).toBeInTheDocument();
    expect(h.recordInvoice).not.toHaveBeenCalled();
  });

  it("will not save figures that do not add up, and says by how much under the Grand total", async () => {
    await billedPair();
    await upload();
    totals("10000");
    expect(screen.getByText("Adds up to ₹10,500, not ₹10,000")).toBeInTheDocument();
    save();
    expect(
      await screen.findByText("The sub total, GST, other charges and discount don’t add up to the grand total on the bill."),
    ).toBeInTheDocument();
    expect(h.recordInvoice).not.toHaveBeenCalled();
  });

  it("will not save with no delivery chosen and Direct unticked", async () => {
    await open();
    await chooseVendor();
    header();
    await upload();
    totals("0");
    save();
    expect(
      await screen.findByText("Choose the deliveries this bill is for, or tick Direct, with no purchase order."),
    ).toBeInTheDocument();
    expect(h.recordInvoice).not.toHaveBeenCalled();
  });

  it("names a blank amount on a billed line beside its box", async () => {
    await open();
    await chooseVendor();
    await tick(L45);
    header();
    await upload();
    totals("0");
    save();
    expect(await screen.findByText("Cardamom amount is required")).toBeInTheDocument();
    expect(h.recordInvoice).not.toHaveBeenCalled();
  });

  it("shows a server refusal the app's usual way, with its code", async () => {
    h.recordInvoice.mockRejectedValue(
      new ApiError({
        code: "KMS-400169",
        message: "One of those deliveries has already been billed.",
        action: "Choose the deliveries again.",
        fieldErrors: [],
      }),
    );
    await billedPair();
    await upload();
    totals("10500");
    save();
    expect(await screen.findByText("KMS-400169")).toBeInTheDocument();
    expect(screen.getByText("One of those deliveries has already been billed.")).toBeInTheDocument();
    expect(h.pushMock).not.toHaveBeenCalled();
  });
});
