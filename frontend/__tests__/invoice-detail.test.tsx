import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import type { InvoiceLineView, InvoicePaymentView, VendorInvoiceDetailView } from "@/lib/api";

/**
 * An existing invoice's page (R-INV-7, R-PAY-3; T-274): the summary with the bill, the items with the
 * bill's totals, "Invoiced vs received", the payments with their proof, and the confirmation that
 * "Create an invoice" sends here.
 *
 * <p>The real `useAuthedQuery` runs, over a stubbed `api`, so what is asserted about who is asked for
 * what (a Kitchen Manager's page never asks for the payments) is what the page actually does.
 */

const h = vi.hoisted(() => ({
  auth: {
    current: {
      status: "signed-in",
      appUser: { role: "TEMPLE_ADMIN", userId: "me" } as { role: string; userId: string } | null,
    },
  },
  // Stable across renders, as the real context's is: useAuthedQuery refetches when it changes.
  getToken: async () => "test-token",
  search: { current: new URLSearchParams() },
  replace: vi.fn(),
  getInvoice: vi.fn(),
  listInvoicePayments: vi.fn(),
  invoiceBill: vi.fn(),
  paymentFile: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: h.replace, push: vi.fn() }),
  useParams: () => ({ id: "inv1" }),
  useSearchParams: () => h.search.current,
  usePathname: () => "/invoices/inv1",
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...h.auth.current, getToken: h.getToken }),
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      getInvoice: h.getInvoice,
      listInvoicePayments: h.listInvoicePayments,
      invoiceBill: h.invoiceBill,
      paymentFile: h.paymentFile,
    },
  };
});

import InvoiceDetailPage from "@/app/invoices/[id]/page";

function line(o: Partial<InvoiceLineView>): InvoiceLineView {
  return {
    id: "l1",
    goodsReceiptLineId: "gl1",
    ingredientId: "t",
    itemName: "Tomato, ripe",
    orderedQty: 10,
    deliveredQty: 9,
    billedQty: 9,
    unit: "KG",
    packSizeId: null,
    packLabel: null,
    packQuantity: null,
    packCount: null,
    amount: 288,
    rate: 32,
    ratePerPack: null,
    ...o,
  };
}

/** Mock design D's invoice, with a pack line added: 4 × Bag (25 Kg) for ₹6,000. */
function invoice(o: Partial<VendorInvoiceDetailView> = {}): VendorInvoiceDetailView {
  return {
    id: "inv1",
    vendorId: "v1",
    vendorName: "Kalasipalya Vegetable Mandi",
    purchaseOrderId: "po44",
    poNumber: "PO-2026-0044",
    direct: false,
    description: null,
    invoiceNumber: "KVM/2026/0917",
    invoiceDate: "2026-09-17",
    amount: 7030,
    dueDate: "2026-10-01",
    scanRef: null,
    status: "PENDING",
    expectedValue: 6920,
    variance: 38,
    overdue: false,
    voidedAt: null,
    voidReason: null,
    creditedAmount: 0,
    createdAt: "2026-09-17T00:00:00Z",
    lines: [
      line({}),
      line({ id: "l2", itemName: "Green chilli, slit", orderedQty: 2, deliveredQty: 1.5, billedQty: 2, amount: 140, rate: 70 }),
      line({
        id: "l3",
        itemName: "Sona masoori rice",
        orderedQty: 100,
        deliveredQty: 100,
        billedQty: 100,
        packSizeId: "bag25",
        packLabel: "Bag (25 Kg)",
        packQuantity: 25,
        packCount: 4,
        amount: 6000,
        rate: 60,
        ratePerPack: 1500,
      }),
    ],
    deliveries: [
      { receiptId: "r44", purchaseOrderId: "po44", poNumber: "PO-2026-0044", receivedOn: "2026-09-12", receivedByName: "Govinda Das" },
    ],
    subTotal: 6428,
    gstAmount: 554.5,
    otherCharges: 50,
    otherChargesNote: "Delivery",
    discount: 2.5,
    grandTotal: 7030,
    bill: {
      id: "bill1",
      kind: "INVOICE_BILL",
      contentType: "image/jpeg",
      sizeBytes: 1000,
      originalName: "KVM-0917.jpg",
      uploadedAt: "2026-09-17T00:00:00Z",
    },
    ...o,
  };
}

/** An invoice recorded before stage 6: a total and a typed scan reference, nothing else. */
const OLD = invoice({
  invoiceNumber: "INV-1",
  amount: 1400,
  expectedValue: 1350,
  variance: 50,
  scanRef: "drive://invoices/INV-1.pdf",
  lines: [],
  deliveries: [],
  subTotal: null,
  gstAmount: null,
  otherCharges: null,
  otherChargesNote: null,
  discount: null,
  grandTotal: null,
  bill: null,
});

function payment(o: Partial<InvoicePaymentView> = {}): InvoicePaymentView {
  return {
    id: "p1",
    paidOn: "2026-09-17",
    amount: 300,
    method: "UPI",
    reference: "UPI 4261 8837 0192",
    note: null,
    recordedByName: "Govinda Das",
    reverses: null,
    reversedBy: null,
    reverseReason: null,
    receivedByName: null,
    attachments: [
      { id: "a1", kind: "PAYMENT_PROOF", contentType: "image/png", sizeBytes: 10, originalName: "UPI-4261.png", uploadedAt: "" },
    ],
    createdAt: "2026-09-17T00:00:00Z",
    ...o,
  };
}

const CASH = payment({
  id: "p2",
  paidOn: "2026-09-18",
  amount: 200,
  method: "CASH",
  reference: null,
  receivedByName: "Manjunath K.",
  attachments: [
    { id: "a2", kind: "CASH_SIGNED_NOTE", contentType: "image/jpeg", sizeBytes: 10, originalName: "cash-note.jpg", uploadedAt: "" },
    { id: "a3", kind: "CASH_RECEIVER_PHOTO", contentType: "image/jpeg", sizeBytes: 10, originalName: null, uploadedAt: "" },
  ],
});

beforeEach(() => {
  h.auth.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
  h.search.current = new URLSearchParams();
  h.replace.mockReset();
  h.getInvoice.mockReset().mockResolvedValue(invoice());
  h.listInvoicePayments.mockReset().mockResolvedValue([payment(), CASH]);
  h.invoiceBill.mockReset().mockResolvedValue(new Blob(["x"], { type: "image/jpeg" }));
  h.paymentFile.mockReset().mockResolvedValue(new Blob(["x"], { type: "image/jpeg" }));
  URL.createObjectURL = vi.fn(() => "blob:test");
  URL.revokeObjectURL = vi.fn();
});
afterEach(() => vi.useRealTimers());

async function open() {
  render(<InvoiceDetailPage />);
  await screen.findByRole("heading", { level: 1 });
}

describe("an invoice's page (R-INV-7)", () => {
  it("shows the summary, the delivery it bills in R-INV-3's words, and the bill's thumbnail, which opens the file", async () => {
    await open();
    expect(screen.getByRole("heading", { level: 1, name: "KVM/2026/0917" })).toBeInTheDocument();
    const summary = screen.getByRole("region", { name: "The invoice" });
    expect(within(summary).getByText("17 Sept 2026")).toBeInTheDocument();
    expect(within(summary).getByText("₹7,030")).toBeInTheDocument();
    const delivery = within(summary).getByText(/delivered 12 Sept/).closest("li")!;
    expect(delivery).toHaveTextContent("PO-2026-0044 · delivered 12 Sept · received by Govinda Das");
    expect(within(delivery).getByRole("link", { name: "PO-2026-0044" })).toHaveAttribute("href", "/orders/po44");
    // The bill is fetched through the permission-checked endpoint, never a URL.
    expect(within(summary).getByRole("button", { name: "Open KVM-0917.jpg" })).toBeInTheDocument();
    await waitFor(() => expect(h.invoiceBill).toHaveBeenCalledWith("inv1", "test-token"));
    within(summary).getByRole("button", { name: "KVM-0917.jpg" }).click();
    expect(await screen.findByRole("dialog", { name: "KVM-0917.jpg" })).toBeInTheDocument();
  });

  it("lists the items read-only, a pack line as T-273 shows it, and the over-billed line in amber", async () => {
    await open();
    const items = screen.getByRole("region", { name: "Items" });
    const heads = within(items).getAllByRole("columnheader").map((th) => th.textContent);
    expect(heads).toEqual(["Item", "Ordered", "Delivered", "Billed qty", "Amount", "Rate"]);
    expect(within(items).queryByRole("textbox")).not.toBeInTheDocument();
    expect(within(items).queryByRole("spinbutton")).not.toBeInTheDocument();

    const rice = within(items).getByText("Sona masoori rice").closest("tr")!;
    const cells = within(rice).getAllByRole("cell");
    expect(cells[3]).toHaveTextContent("4 × Bag (25 Kg)100 Kg");
    expect(cells[4]).toHaveTextContent("₹6,000");
    expect(cells[5]).toHaveTextContent("₹1,500 / bag · ₹60 / Kg");

    const tomato = within(items).getByText("Tomato, ripe").closest("tr")!;
    expect(within(tomato).getAllByRole("cell")[5]).toHaveTextContent("₹32 / Kg");

    const chilli = within(items).getByText("Green chilli, slit").closest("td")!;
    const warn = within(chilli).getByText("Billed 2 Kg, 1.5 Kg delivered");
    expect(warn).toHaveClass("text-warning");
  });

  it("shows the bill's totals in the read-only block, with the tick when they add up", async () => {
    await open();
    const totals = screen.getByTestId("invoice-totals");
    expect(within(totals).getByTestId("sub-total")).toHaveTextContent("6,428");
    expect(within(totals).getByText("GST")).toBeInTheDocument();
    expect(within(totals).getByText("Delivery")).toBeInTheDocument();
    expect(within(totals).getByTestId("grand-total")).toHaveTextContent("7,030");
    expect(within(totals).getByRole("img", { name: "Adds up to the grand total" })).toBeInTheDocument();
    expect(within(totals).queryByRole("spinbutton")).not.toBeInTheDocument();
    // Design D's compact foot (T-277): the rupee sign on each figure and no "Amount (₹)" heading.
    expect(within(totals).getByTestId("sub-total")).toHaveTextContent(/^₹6,428$/);
    expect(within(totals).queryByText("Amount (₹)")).not.toBeInTheDocument();
  });

  // T-277: the page is on the shared PageHeader again (T-274 had copied its markup to let three
  // actions wrap on a phone). All three sit in PageHeader's one wrapping actions box.
  it("puts Pay, Record a credit note and Void in the shared header's wrapping actions box", async () => {
    await open();
    const pay = screen.getByRole("button", { name: "Pay this invoice" });
    const box = pay.parentElement!;
    expect(box).toHaveClass("flex", "min-w-0", "flex-wrap");
    expect(box).not.toHaveClass("flex-none");
    expect([...box.children].map((b) => b.textContent)).toEqual([
      "Pay this invoice",
      "Record a credit note",
      "Void this bill",
    ]);
    expect(box.closest("header")).toContainElement(screen.getByRole("heading", { level: 1 }));
  });

  it("shows Invoiced vs received as the server sends it, and a null as a dash, never ₹0", async () => {
    await open();
    let section = screen.getByRole("region", { name: "Invoiced vs received" });
    expect(within(section).getByText("₹6,428")).toBeInTheDocument();
    expect(within(section).getByText("₹6,920")).toBeInTheDocument();
    expect(within(section).getByText("₹38 more than received")).toHaveClass("text-warning");
    expect(within(section).getByText("Green chilli, slit: billed 2 Kg, 1.5 Kg delivered")).toBeInTheDocument();

    h.getInvoice.mockResolvedValue(invoice({ expectedValue: null, variance: null }));
    cleanup();
    await open();
    section = screen.getByRole("region", { name: "Invoiced vs received" });
    expect(within(section).getAllByText("—")).toHaveLength(2);
    expect(within(section).queryByText("₹0")).not.toBeInTheDocument();
  });

  it("still reads sensibly for an invoice recorded before stage 6: total, scan reference, no items, the old comparison", async () => {
    h.getInvoice.mockResolvedValue(OLD);
    await open();
    expect(screen.getByRole("heading", { level: 1, name: "INV-1" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Items" })).not.toBeInTheDocument();
    expect(screen.queryByTestId("invoice-totals")).not.toBeInTheDocument();
    const summary = screen.getByRole("region", { name: "The invoice" });
    expect(within(summary).getByText("drive://invoices/INV-1.pdf")).toBeInTheDocument();
    expect(within(summary).getByText("Nothing uploaded")).toBeInTheDocument();
    expect(h.invoiceBill).not.toHaveBeenCalled();
    const section = screen.getByRole("region", { name: "Invoiced vs received" });
    expect(within(section).getByText("Invoiced")).toBeInTheDocument();
    expect(within(section).getByText("₹50 more than received")).toBeInTheDocument();
    expect(within(summary).getByRole("link", { name: "PO-2026-0044" })).toHaveAttribute("href", "/orders/po44");
  });

  it("drops Ordered and Delivered on a direct buy, and the comparison with them", async () => {
    h.getInvoice.mockResolvedValue(
      invoice({
        direct: true,
        purchaseOrderId: null,
        poNumber: null,
        description: "Cash market vegetables",
        expectedValue: null,
        variance: null,
        deliveries: [],
        lines: [line({ goodsReceiptLineId: null, orderedQty: null, deliveredQty: null })],
        subTotal: 288,
        gstAmount: 0,
        otherCharges: 0,
        otherChargesNote: null,
        discount: 0,
        grandTotal: 288,
        amount: 288,
      })
    );
    h.listInvoicePayments.mockResolvedValue([]);
    await open();
    expect(screen.getByText("Cash market vegetables")).toBeInTheDocument();
    expect(screen.getByText(/direct — no purchase order/i)).toBeInTheDocument();
    const heads = within(screen.getByRole("region", { name: "Items" })).getAllByRole("columnheader");
    expect(heads.map((th) => th.textContent)).toEqual(["Item", "Billed qty", "Amount", "Rate"]);
    expect(screen.queryByRole("region", { name: "Invoiced vs received" })).not.toBeInTheDocument();
  });

  // T-293 (VERIFY-A defect 3): a line kept in grams says its rate per Kg, as the vendor page and
  // the order sheet say the same price — never "₹0.50 / gm" because the quantity is under 1,000 gm.
  it("says a gram line's rate per Kg, and a 500 gm pack per pack and per Kg", async () => {
    h.getInvoice.mockResolvedValue(
      invoice({
        lines: [
          line({ id: "g1", itemName: "Tea", billedQty: 500, deliveredQty: 500, orderedQty: 500, unit: "GM", amount: 250, rate: 0.5 }),
          line({
            id: "g2",
            itemName: "Cardamom",
            billedQty: 1000,
            deliveredQty: 1000,
            orderedQty: 1000,
            unit: "GM",
            packSizeId: "p500",
            packLabel: "500 gm",
            packQuantity: 500,
            packCount: 2,
            amount: 3000,
            rate: 3,
            ratePerPack: 1500,
          }),
        ],
      })
    );
    await open();
    const items = screen.getByRole("region", { name: "Items" });
    const rateOf = (name: string) => within(within(items).getByText(name).closest("tr")!).getAllByRole("cell")[5];
    expect(rateOf("Tea")).toHaveTextContent(/^₹500 \/ Kg$/);
    expect(rateOf("Cardamom")).toHaveTextContent(/^₹1,500 \/ 500 gm · ₹3,000 \/ Kg$/);
    expect(items.textContent).not.toMatch(/\/ ?(gm|ml)\b/);
  });

  // T-302, C-2 as T-299 fixed it on Deliveries: one pack with no name is its own size, so "500 gm"
  // under "1 × 500 gm" is dropped. Counted in packs, not by comparing text ("0.5 Kg" over 500 gm is a
  // repeat too); two packs keep their total, and a named pack keeps its size.
  it("says one unnamed pack once, keeping the amount under two packs and under a named pack (T-302)", async () => {
    const pack = (id: string, itemName: string, qty: number, unit: string, packLabel: string, packQuantity: number, packCount: number) =>
      line({
        id, itemName, orderedQty: qty, deliveredQty: qty, billedQty: qty, unit,
        packSizeId: `p-${id}`, packLabel, packQuantity, packCount, amount: 100, rate: 100 / qty, ratePerPack: 100 / packCount,
      });
    h.getInvoice.mockResolvedValue(
      invoice({
        lines: [
          pack("a", "Tea", 500, "GM", "500 gm", 500, 1),
          pack("b", "Ghee", 500, "GM", "0.5 Kg", 500, 1),
          pack("c", "Cardamom", 1000, "GM", "500 gm", 500, 2),
          pack("d", "Sona masoori rice", 25, "KG", "Bag (25 Kg)", 25, 1),
        ],
      })
    );
    await open();
    const items = screen.getByRole("region", { name: "Items" });
    const qty = (name: string) =>
      within(within(items).getByText(name).closest("tr")!).getAllByRole("cell").slice(1, 4).map((c) => c.textContent);
    expect(qty("Tea")).toEqual(["1 × 500 gm", "1 × 500 gm", "1 × 500 gm"]);
    expect(qty("Ghee")).toEqual(["1 × 0.5 Kg", "1 × 0.5 Kg", "1 × 0.5 Kg"]);
    expect(qty("Cardamom")).toEqual(["2 × 500 gm1 Kg", "2 × 500 gm1 Kg", "2 × 500 gm1 Kg"]);
    expect(qty("Sona masoori rice")).toEqual(["1 × Bag (25 Kg)25 Kg", "1 × Bag (25 Kg)25 Kg", "1 × Bag (25 Kg)25 Kg"]);
  });

  it("says Unpaid, the list's word, on a bill still owed", async () => {
    await open();
    expect(screen.getByText("Unpaid")).toBeInTheDocument();
    expect(screen.queryByText("Pending")).not.toBeInTheDocument();
  });
});

describe("a long value with no spaces stays inside its card (T-304)", () => {
  it("lets a detail break inside itself, at its separators first, and keeps short values whole", async () => {
    h.getInvoice.mockResolvedValue(invoice({ description: "accounts.receivable@sri-lakshmi-traders.co.in" }));
    await open();
    const zw = "\u200B";
    const summary = screen.getByRole("region", { name: "The invoice" });
    const dd = within(summary).getByText("Description").nextElementSibling as HTMLElement;
    expect(dd.textContent).toBe(`accounts.${zw}receivable@${zw}sri-${zw}lakshmi-${zw}traders.${zw}co.${zw}in`);
    // Anywhere, only when a piece is still wider than its place; and a grid column may give way.
    expect(dd).toHaveClass("[overflow-wrap:anywhere]");
    expect(dd.parentElement).toHaveClass("min-w-0");
    // A PO number is never a pasted token.
    for (const link of within(summary).getAllByRole("link", { name: "PO-2026-0044" })) {
      expect(link.textContent).toBe("PO-2026-0044");
    }
  });
});

describe("payments on the page (R-PAY-1, R-PAY-3)", () => {
  it("shows a Temple Admin what is paid and still owed, Paid by, and each payment's proof, two for cash", async () => {
    await open();
    const section = await screen.findByRole("region", { name: "Payments" });
    await within(section).findByText("Paid to date");
    expect(within(section).getByText("₹500")).toBeInTheDocument();
    expect(within(section).getByText("₹6,530")).toBeInTheDocument();
    const heads = within(section).getAllByRole("columnheader").map((th) => th.textContent);
    expect(heads).toContain("Paid by");
    expect(heads).not.toContain("Recorded by");

    const upi = within(section).getByText("UPI 4261 8837 0192").closest("tr")!;
    expect(within(upi).getAllByRole("button", { name: /^Open / })).toHaveLength(1);
    const cash = within(section).getByText("Received by Manjunath K.").closest("tr")!;
    const thumbs = within(cash).getAllByRole("button", { name: /^Open / });
    expect(thumbs.map((b) => b.getAttribute("aria-label"))).toEqual([
      "Open cash-note.jpg",
      "Open Photo of the person who took the cash",
    ]);
    expect(within(cash).getByText("Cash")).toBeInTheDocument();
    await waitFor(() => expect(h.paymentFile).toHaveBeenCalledWith("inv1", "p2", "a3", "test-token"));
  });

  // T-300 (defect N1 of the second invoices verification). A reference is free text: a bank's UTR,
  // or for cash "Received by" and a full name. As a short value (`kms-fixed`, never wraps) a
  // 34-character one was a line 315-334px long on a 390px phone, and the page scrolled sideways by
  // 10px. jsdom has no layout, so this pins the classes that decide it; the widths themselves were
  // measured in a browser and are in docs/work/proof/T-300.md.
  it("lets a long reference or cash receiver's name wrap, and keeps it on the card's second line on a phone", async () => {
    h.listInvoicePayments.mockResolvedValue([
      payment({ reference: "VERIFY2-D UTR SBIN0226019876543210" }),
      { ...CASH, receivedByName: "Manjunath Krishnamurthy Gowda" },
    ]);
    await open();
    const section = await screen.findByRole("region", { name: "Payments" });
    const head = within(section).getByRole("columnheader", { name: "Reference" });
    const cells = [
      within(section).getByText("VERIFY2-D UTR SBIN0226019876543210"),
      within(section).getByText("Received by Manjunath Krishnamurthy Gowda"),
    ];
    for (const el of [head, ...cells]) {
      // A text column wraps between words; a short value never does.
      expect(el).toHaveClass("kms-flex");
      expect(el).not.toHaveClass("kms-fixed");
      // On a phone card it stays among the short values, between Method and Proof, as the mock has it.
      expect(el).toHaveClass("max-lg:!order-2");
    }
    for (const td of cells) expect(td).toHaveAttribute("data-label", "Reference");
  });

  it("gives a Temple Admin the Pay this invoice button while something is owed", async () => {
    await open();
    expect(await screen.findByRole("button", { name: "Pay this invoice" })).toBeInTheDocument();
  });

  it("offers no Pay button on a bill paid in full", async () => {
    h.getInvoice.mockResolvedValue(invoice({ status: "PAID" }));
    h.listInvoicePayments.mockResolvedValue([payment({ amount: 7030 })]);
    await open();
    await screen.findByText("Paid to date");
    expect(screen.queryByRole("button", { name: "Pay this invoice" })).not.toBeInTheDocument();
  });

  it("gives a Kitchen Manager no Pay button, no payment form, no payments, and never asks for them", async () => {
    h.auth.current = { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "me" } };
    await open();
    expect(screen.getByRole("region", { name: "Items" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Pay this invoice" })).not.toBeInTheDocument();
    expect(screen.queryByRole("form", { name: "Pay this invoice" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Payments" })).not.toBeInTheDocument();
    expect(screen.queryByText(/paid to date/i)).not.toBeInTheDocument();
    expect(h.listInvoicePayments).not.toHaveBeenCalled();
  });
});

describe("the confirmation Create an invoice comes back with", () => {
  it("says the invoice was recorded, once, and clears the address", async () => {
    h.search.current = new URLSearchParams("recorded=KVM%2F2026%2F0917");
    const { rerender } = render(<InvoiceDetailPage />);
    expect(await screen.findByText("Invoice KVM/2026/0917 was recorded.")).toBeInTheDocument();
    expect(h.replace).toHaveBeenCalledTimes(1);
    expect(h.replace).toHaveBeenCalledWith("/invoices/inv1");
    rerender(<InvoiceDetailPage />);
    expect(screen.getAllByText("Invoice KVM/2026/0917 was recorded.")).toHaveLength(1);
    expect(h.replace).toHaveBeenCalledTimes(1);
  });

  it("warns, and keeps the warning, when the number is already used by this vendor", async () => {
    h.search.current = new URLSearchParams("recorded=KVM%2F2026%2F0917&duplicate=1");
    render(<InvoiceDetailPage />);
    expect(await screen.findByText("Another invoice from this vendor already uses that number.")).toBeInTheDocument();
    expect(screen.getByText("Invoice KVM/2026/0917 was recorded.")).toBeInTheDocument();
    expect(h.replace).toHaveBeenCalledTimes(1);
  });
});
