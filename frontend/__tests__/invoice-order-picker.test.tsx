import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { BillableDeliveryView, VendorView } from "@/lib/api";

/**
 * What an invoice bills, chosen after the vendor (T-082, rebuilt for R-INV-3 in T-273).
 *
 * <p>T-082 fixed an invoice that could quote another vendor's purchase order: the field was a pasted
 * id, asked separately from the vendor, and nothing checked the pair. Its fix was a dropdown of the
 * chosen vendor's own open orders, re-drawn when the vendor changed. R-INV-3 moved the question from
 * the order to the delivery or deliveries being billed (vendors bill per delivery), so the dropdown
 * became one tick box per delivery this vendor has not billed yet. The guarantees T-082 asserted
 * carry over unchanged, and are what this file still asserts: the vendor comes first, only that
 * vendor's deliveries are offered, and a vendor change drops what was ticked under the old one, so a
 * mismatched pair cannot be posted from a picker that looks right.
 *
 * <p>`useAuthedQuery` is deliberately NOT mocked: the re-filter is its behaviour.
 */

const { authRef, pushMock, listVendors, listIngredients, listBillableDeliveries, recordInvoice } = vi.hoisted(() => ({
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
      signOut: vi.fn(),
      switchTemple: vi.fn(),
    } as Record<string, unknown>,
  },
  pushMock: vi.fn(),
  listVendors: vi.fn(),
  listIngredients: vi.fn(),
  listBillableDeliveries: vi.fn(),
  recordInvoice: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({}),
  usePathname: () => "/invoices/new",
}));
vi.mock("@/lib/auth-context", () => ({ useAuth: () => authRef.current }));
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, listVendors, listIngredients, listBillableDeliveries, recordInvoice },
  };
});

import NewInvoicePage from "@/app/invoices/new/page";

const vendor = (id: string, name: string) => ({ id, name, active: true }) as unknown as VendorView;
const GOVIND = vendor("v1", "Govind Wholesale");
const SRI = vendor("v2", "Sri Traders");

function delivery(
  receiptId: string,
  poNumber: string,
  receivedOn: string,
  by: string | null,
  deliveredQty = 9,
): BillableDeliveryView {
  return {
    receiptId,
    purchaseOrderId: `po-${receiptId}`,
    poNumber,
    receivedOn,
    receivedByName: by,
    lines: [
      {
        goodsReceiptLineId: `gl-${receiptId}`,
        ingredientId: "tom",
        itemName: `Tomato from ${poNumber}`,
        orderedQty: 10,
        deliveredQty,
        unit: "KG",
        packSizeId: null,
        packLabel: null,
        packQuantity: null,
      },
    ],
  };
}

// Two vendors with deliveries each. Neither vendor's may ever appear under the other.
const GOVIND_DELIVERIES = [
  delivery("r1", "PO-2026-0044", "2026-09-12", "Govinda Das"),
  delivery("r2", "PO-2026-0045", "2026-09-14", null),
];
const SRI_DELIVERIES = [delivery("r9", "PO-2026-0099", "2026-09-10", "Madhava")];

const ticks = () => screen.queryAllByRole("checkbox").filter((c) => c.closest('[role="group"]'));
const tickNames = () => ticks().map((c) => c.closest("label")?.textContent ?? "");

async function chooseVendor(id: string) {
  fireEvent.change(document.querySelector('select[name="vendorId"]')!, { target: { value: id } });
  await waitFor(() => expect(listBillableDeliveries).toHaveBeenCalledWith(id, "test-token"));
}

describe("choosing what an invoice bills", () => {
  beforeEach(() => {
    listVendors.mockReset().mockResolvedValue([GOVIND, SRI]);
    listIngredients.mockReset().mockResolvedValue([]);
    listBillableDeliveries
      .mockReset()
      .mockImplementation(async (vendorId: string) =>
        vendorId === "v1" ? GOVIND_DELIVERIES : vendorId === "v2" ? SRI_DELIVERIES : [],
      );
    recordInvoice.mockReset();
    pushMock.mockReset();
  });

  it("never asks for a pasted id: no order or delivery box to type into", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    expect(document.querySelector('input[name="purchaseOrderId"]')).toBeNull();
    expect(screen.queryByText(/paste/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/purchase order id/i)).not.toBeInTheDocument();
    expect(screen.getByText("Deliveries being billed")).toBeInTheDocument();
  });

  it("cannot be answered before the vendor is", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    expect(screen.getByText("Choose a vendor first.")).toBeInTheDocument();
    expect(ticks()).toHaveLength(0);
    expect(listBillableDeliveries).not.toHaveBeenCalled();
  });

  it("offers only the chosen vendor's deliveries not yet billed, named as R-INV-3 names them", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    await chooseVendor("v1");
    await waitFor(() =>
      expect(tickNames()).toEqual([
        "PO-2026-0044 · delivered 12 Sept · received by Govinda Das · 9 Kg",
        "PO-2026-0045 · delivered 14 Sept · 9 Kg",
      ]),
    );
    expect(tickNames().join(" ")).not.toContain("PO-2026-0099");
  });

  // VERIFY-D's defect D1 (T-290): PO-2026-0051 came as 45 Kg and then 5 Kg on 19 Sept, both ticks
  // read "PO-2026-0051 · delivered 19 Sept · received by Karuna Murti Das", and the list came in the
  // server's order, 0051, 0052, 0050, 0051, 0053. Given in that scrambled order here, so the sort is
  // the page's as well as the server's.
  it("tells two deliveries of one order on one day apart by what came, and lists them by order then day", async () => {
    const K = "Karuna Murti Das";
    listBillableDeliveries.mockResolvedValue([
      delivery("a", "PO-2026-0051", "2026-09-19", K, 45),
      delivery("b", "PO-2026-0052", "2026-09-19", K, 100),
      { ...delivery("c", "PO-2026-0050", "2026-09-19", K), lines: [...delivery("c1", "x", "", K).lines, ...delivery("c2", "x", "", K).lines] },
      delivery("d", "PO-2026-0051", "2026-09-19", K, 5),
      delivery("e", "PO-2026-0053", "2026-09-18", K, 10),
      delivery("f", "PO-2026-0051", "2026-09-17", K, 20),
    ]);
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    await chooseVendor("v1");
    await waitFor(() =>
      expect(tickNames()).toEqual([
        "PO-2026-0050 · delivered 19 Sept · received by Karuna Murti Das · 2 items",
        "PO-2026-0051 · delivered 17 Sept · received by Karuna Murti Das · 20 Kg",
        "PO-2026-0051 · delivered 19 Sept · received by Karuna Murti Das · 45 Kg",
        "PO-2026-0051 · delivered 19 Sept · received by Karuna Murti Das · 5 Kg",
        "PO-2026-0052 · delivered 19 Sept · received by Karuna Murti Das · 100 Kg",
        "PO-2026-0053 · delivered 18 Sept · received by Karuna Murti Das · 10 Kg",
      ]),
    );
    expect(new Set(tickNames()).size).toBe(6);
    // Each tick is its own delivery: the 5 Kg one pulls in 5 Kg, not the 45.
    fireEvent.click(screen.getByRole("checkbox", { name: /PO-2026-0051 · delivered 19 Sept .* · 5 Kg$/ }));
    expect(await screen.findByLabelText("Tomato from PO-2026-0051 billed quantity")).toHaveValue(5);
  });

  it("numbers two deliveries that still match, same order, day and amount, in the order they came", async () => {
    listBillableDeliveries.mockResolvedValue([
      delivery("a", "PO-2026-0051", "2026-09-19", null, 5),
      delivery("b", "PO-2026-0051", "2026-09-19", null, 5),
      delivery("c", "PO-2026-0051", "2026-09-19", null, 45),
    ]);
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    await chooseVendor("v1");
    await waitFor(() =>
      expect(tickNames()).toEqual([
        "PO-2026-0051 · delivered 19 Sept · 5 Kg · 1st that day",
        "PO-2026-0051 · delivered 19 Sept · 5 Kg · 2nd that day",
        "PO-2026-0051 · delivered 19 Sept · 45 Kg",
      ]),
    );
    // Each is still its own tick.
    fireEvent.click(screen.getByRole("checkbox", { name: /2nd that day/ }));
    expect(ticks().map((c) => (c as HTMLInputElement).checked)).toEqual([false, true, false]);
  });

  it("re-draws when the vendor changes, and drops what was ticked under the old one", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    await chooseVendor("v1");
    fireEvent.click(await screen.findByRole("checkbox", { name: /PO-2026-0044/ }));
    expect(screen.getByText("Tomato from PO-2026-0044")).toBeInTheDocument();

    await chooseVendor("v2");
    await waitFor(() => expect(tickNames()).toEqual(["PO-2026-0099 · delivered 10 Sept · received by Madhava · 9 Kg"]));
    expect(screen.queryByText("Tomato from PO-2026-0044")).not.toBeInTheDocument();

    // And back: nothing is still ticked from before.
    await chooseVendor("v1");
    await waitFor(() => expect(ticks()).toHaveLength(2));
    expect(ticks().every((c) => !(c as HTMLInputElement).checked)).toBe(true);
  });

  it("says so, and points at the way out, when a vendor has nothing to bill", async () => {
    listBillableDeliveries.mockResolvedValue([]);
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    await chooseVendor("v1");
    expect(
      await screen.findByText("Nothing from this vendor is waiting to be billed. For a cash-market buy, tick Direct above."),
    ).toBeInTheDocument();
  });

  it("swaps the deliveries for a description when the buy was direct", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    await chooseVendor("v1");
    fireEvent.click(screen.getByLabelText("Direct, with no purchase order"));
    expect(screen.queryByText("Deliveries being billed")).not.toBeInTheDocument();
    expect(document.querySelector('input[name="description"]')).toBeInTheDocument();
  });

  it("names every blank box when Save invoice is pressed, and records nothing (T-162)", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    fireEvent.click(screen.getByRole("button", { name: "Save invoice" }));
    expect(await screen.findByText("Vendor is required")).toBeInTheDocument();
    expect(screen.getByText("Invoice number is required")).toBeInTheDocument();
    expect(screen.getByText("Invoice date is required")).toBeInTheDocument();
    expect(screen.getByText("Grand total is required")).toBeInTheDocument();
    expect(screen.getByText("Copy of the bill is required")).toBeInTheDocument();
    expect(recordInvoice).not.toHaveBeenCalled();
  });
});
