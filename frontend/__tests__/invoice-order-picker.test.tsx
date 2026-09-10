import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { PurchaseOrderView, VendorView } from "@/lib/api";

/**
 * The purchase order on the record-an-invoice screen (T-082).
 *
 * <p>The field was a text box labelled "Purchase order id", asking for a database UUID pasted from
 * another screen, while the vendor was asked separately as though the two were unrelated. Nothing
 * checked that the pair agreed, so an invoice against one vendor could quote another vendor's order
 * — and the variance, which is money owed, was then computed from that other order.
 *
 * <p>What is asserted here is the fix's shape rather than its symptoms: the vendor is chosen first,
 * the order is a dropdown drawn from that vendor's own open orders, and it is re-drawn when the
 * vendor changes. That last one is the transition most likely to be got wrong, because React keeps
 * the value of a {@code <select>} whose chosen {@code <option>} has been removed — so a stale order
 * id can survive a vendor change and be posted, which is the original defect wearing a dropdown.
 *
 * <p>`useAuthedQuery` is deliberately NOT mocked. The re-filter *is* its behaviour — the fetcher
 * closes over the vendor and sits in the effect's dependency list — and a stub returning canned data
 * by call order would assert nothing about the thing under test. What is mocked is the API, which is
 * where the network would be.
 */

const { authRef, pushMock, listVendors, listOpenPurchaseOrdersForVendor, recordInvoice } = vi.hoisted(
  () => ({
    // One object, replaced only when the role changes. `useAuthedQuery` lists the auth object's
    // members in its effect dependencies, so a mock building a fresh `getToken` per render
    // re-fetches for ever and every assertion times out on a screen that never settles.
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
    listVendors: vi.fn(),
    listOpenPurchaseOrdersForVendor: vi.fn(),
    recordInvoice: vi.fn(),
  })
);

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
    api: { ...actual.api, listVendors, listOpenPurchaseOrdersForVendor, recordInvoice },
  };
});

import NewInvoicePage from "@/app/invoices/new/page";

function vendor(id: string, name: string): VendorView {
  return {
    id,
    name,
    contactPerson: null,
    phone: "+919812345678",
    email: null,
    address: null,
    gstin: null,
    preferredLanguage: "hi",
    notes: null,
    contractEndDate: null,
    contractEndingSoon: false,
    active: true,
    whatsappReachable: true,
    createdAt: "2026-08-01T00:00:00Z",
  };
}

function order(o: Partial<PurchaseOrderView> & { id: string; poNumber: string; vendorId: string }): PurchaseOrderView {
  return {
    vendorName: "Govind Wholesale",
    status: "RECEIVED",
    orderDate: "2026-08-01",
    neededBy: null,
    deliveryLocation: null,
    notes: null,
    cancelReason: null,
    // Stated rather than left off: PurchaseOrderView declares it required (T-126), so every
    // construction site has to say whether this order is held against the vendor. None of these
    // fixtures is a cancellation, so none of them is.
    vendorAbandoned: false,
    sentAt: "2026-08-01T00:00:00Z",
    cancelledAt: null,
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

const GOVIND = vendor("v1", "Govind Wholesale");
const SRI = vendor("v2", "Sri Traders");

// Two vendors with an order each. The whole point of the pair is that neither vendor's order may
// ever appear under the other.
const GOVIND_ORDERS = [
  order({ id: "po1", poNumber: "PO-2026-0042", vendorId: "v1", neededBy: "2026-08-20" }),
  order({ id: "po2", poNumber: "PO-2026-0043", vendorId: "v1", status: "PARTIALLY_RECEIVED" }),
];
const SRI_ORDERS = [order({ id: "po9", poNumber: "PO-2026-0099", vendorId: "v2", status: "SENT" })];

function optionsOf(name: string): string[] {
  const el = document.querySelector(`select[name="${name}"]`) as HTMLSelectElement | null;
  if (!el) throw new Error(`no <select name="${name}">`);
  return Array.from(el.options).map((o) => o.textContent ?? "");
}

function selectEl(name: string): HTMLSelectElement {
  const el = document.querySelector(`select[name="${name}"]`) as HTMLSelectElement | null;
  if (!el) throw new Error(`no <select name="${name}">`);
  return el;
}

/** Chooses a vendor and waits for that vendor's orders to arrive in the dropdown. */
async function chooseVendor(id: string) {
  fireEvent.change(selectEl("vendorId"), { target: { value: id } });
  await waitFor(() =>
    expect(listOpenPurchaseOrdersForVendor).toHaveBeenCalledWith(id, "test-token")
  );
}

describe("choosing the purchase order on an invoice", () => {
  beforeEach(() => {
    listVendors.mockReset().mockResolvedValue([GOVIND, SRI]);
    listOpenPurchaseOrdersForVendor
      .mockReset()
      .mockImplementation(async (vendorId: string) =>
        vendorId === "v1" ? GOVIND_ORDERS : vendorId === "v2" ? SRI_ORDERS : []
      );
    recordInvoice.mockReset().mockResolvedValue({ invoice: { id: "inv1" }, duplicateWarning: false });
    pushMock.mockReset();
  });

  it("never asks for a pasted id — the order is a dropdown, not a text box", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");

    expect(document.querySelector('input[name="purchaseOrderId"]')).toBeNull();
    expect(selectEl("purchaseOrderId")).toBeInTheDocument();
    // The label lost the word "id" with the field, and the placeholder that told a person to go and
    // copy one is gone with it.
    expect(screen.getByText("Purchase order")).toBeInTheDocument();
    expect(screen.queryByText(/paste/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/purchase order id/i)).not.toBeInTheDocument();
  });

  it("cannot be answered before the vendor is", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");

    expect(selectEl("purchaseOrderId")).toBeDisabled();
    expect(optionsOf("purchaseOrderId")).toEqual(["Choose a vendor first"]);
    expect(listOpenPurchaseOrdersForVendor).not.toHaveBeenCalled();
  });

  it("offers only the chosen vendor's open orders, named as a person would know them", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    await chooseVendor("v1");

    await waitFor(() =>
      expect(optionsOf("purchaseOrderId")).toEqual([
        "Choose an order…",
        "PO-2026-0042 · Received · needed 20 Aug",
        "PO-2026-0043 · Partially received",
      ])
    );
    expect(selectEl("purchaseOrderId")).not.toBeDisabled();
    expect(optionsOf("purchaseOrderId").join(" ")).not.toContain("PO-2026-0099");
  });

  it("re-filters when the vendor changes, and drops the order chosen under the old one", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");

    await chooseVendor("v1");
    await waitFor(() => expect(optionsOf("purchaseOrderId")).toHaveLength(3));
    fireEvent.change(selectEl("purchaseOrderId"), { target: { value: "po1" } });
    expect(selectEl("purchaseOrderId").value).toBe("po1");

    await chooseVendor("v2");
    await waitFor(() =>
      expect(optionsOf("purchaseOrderId")).toEqual(["Choose an order…", "PO-2026-0099 · Sent"])
    );
    // The old vendor's order is gone from the list AND gone from the field. A select whose chosen
    // option is removed keeps its value in React, which is how a mismatched pair would still reach
    // the server through a dropdown that looks right.
    expect(selectEl("purchaseOrderId").value).toBe("");
  });

  it("says so, and points at the way out, when a vendor has nothing open", async () => {
    listOpenPurchaseOrdersForVendor.mockResolvedValue([]);
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    await chooseVendor("v1");

    // Said twice on purpose and matched separately for it: the dropdown's own unchosen option says
    // there is nothing to choose, and the line beneath says what to do instead.
    await waitFor(() =>
      expect(optionsOf("purchaseOrderId")).toEqual(["No open orders for this vendor"])
    );
    expect(await screen.findByText(/cash-market buy/i)).toBeInTheDocument();
  });

  it("posts the id it chose for the reader, having never shown it", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    await chooseVendor("v1");
    await waitFor(() => expect(optionsOf("purchaseOrderId")).toHaveLength(3));
    fireEvent.change(selectEl("purchaseOrderId"), { target: { value: "po2" } });
    fillTheRest();

    fireEvent.submit(screen.getByRole("form", { name: /record an invoice/i }));

    await waitFor(() => expect(recordInvoice).toHaveBeenCalled());
    expect(recordInvoice.mock.calls[0][0]).toMatchObject({
      vendorId: "v1",
      purchaseOrderId: "po2",
      description: null,
      invoiceNumber: "INV-7",
      amount: 1350,
    });
  });

  it("still records a direct purchase, which has no order at all", async () => {
    render(<NewInvoicePage />);
    await screen.findByText("Govind Wholesale");
    fireEvent.change(selectEl("vendorId"), { target: { value: "v1" } });
    fireEvent.click(screen.getByLabelText(/direct, with no purchase order/i));

    expect(document.querySelector('select[name="purchaseOrderId"]')).toBeNull();
    const description = document.querySelector('input[name="description"]') as HTMLInputElement;
    fireEvent.change(description, { target: { value: "Cash market vegetables" } });
    fillTheRest();

    fireEvent.submit(screen.getByRole("form", { name: /record an invoice/i }));

    await waitFor(() => expect(recordInvoice).toHaveBeenCalled());
    expect(recordInvoice.mock.calls[0][0]).toMatchObject({
      vendorId: "v1",
      purchaseOrderId: null,
      description: "Cash market vegetables",
    });
  });
});

/** Everything the form asks for that is not the vendor or the order. */
function fillTheRest() {
  const set = (name: string, value: string) =>
    fireEvent.change(document.querySelector(`input[name="${name}"]`) as HTMLInputElement, {
      target: { value },
    });
  set("invoiceNumber", "INV-7");
  set("amount", "1350");
  set("invoiceDate", "2026-08-01");
}
