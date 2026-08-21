import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import type { VendorInvoiceView, VendorView } from "@/lib/api";

// Two useAuthedQuery calls in order: invoices, then active vendors.
const { authRef, returnsRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: { current: [] as Array<{ data: unknown; error: null; loading: boolean }>, i: 0 },
  reloadMock: vi.fn(),
}));

// The screen reads its own address bar now (item 22), so the stub has to answer both halves of
// next/navigation: what the URL says, and what a click asks the router to do with it.
const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "id-1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => {
    const list = returnsRef.current;
    const value = list[returnsRef.i % list.length];
    returnsRef.i += 1;
    return { ...value, reload: reloadMock };
  },
}));

import InvoicesPage from "@/app/invoices/page";
import NewInvoicePage from "@/app/invoices/new/page";

function invoice(o: Partial<VendorInvoiceView>): VendorInvoiceView {
  return {
    id: "inv1",
    vendorId: "v1",
    vendorName: "Govind Wholesale",
    purchaseOrderId: "po1",
    poNumber: "PO-2026-0042",
    direct: false,
    description: null,
    invoiceNumber: "INV-1",
    invoiceDate: "2026-08-01",
    amount: 1400,
    dueDate: "2026-01-31",
    scanRef: null,
    status: "PENDING",
    expectedValue: 1350,
    variance: 50,
    overdue: true,
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

const VENDORS: VendorView[] = [];

describe("invoices", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    returnsRef.current = [
      { data: [invoice({})], error: null, loading: false },
      { data: VENDORS, error: null, loading: false },
    ];
    returnsRef.i = 0;
    reloadMock.mockReset();
  });

  it("lists invoices with the overdue badge and a price variance", () => {
    render(<InvoicesPage />);
    expect(screen.getByRole("heading", { name: /invoices/i })).toBeInTheDocument();
    expect(screen.getByText("INV-1")).toBeInTheDocument();
    expect(screen.getByText("Overdue")).toBeInTheDocument(); // the badge; the filter reads "Overdue only"
    expect(screen.getByText(/variance ₹50/i)).toBeInTheDocument();
  });

  it("opens the invoice from its number — the row was inert before (A8)", () => {
    render(<InvoicesPage />);
    expect(screen.getByRole("link", { name: "INV-1" })).toHaveAttribute("href", "/invoices/inv1");
  });

  it("distinguishes a direct invoice", () => {
    returnsRef.current = [
      { data: [invoice({ direct: true, purchaseOrderId: null, poNumber: null, expectedValue: null, variance: null, overdue: false, description: "Cash veg" })], error: null, loading: false },
      { data: VENDORS, error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<InvoicesPage />);
    expect(screen.getByText("Direct")).toBeInTheDocument();
  });

  it("shows an empty state with no invoices", () => {
    returnsRef.current = [
      { data: [], error: null, loading: false },
      { data: VENDORS, error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<InvoicesPage />);
    expect(screen.getByText(/no invoices/i)).toBeInTheDocument();
  });
});

describe("recording an invoice", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    returnsRef.current = [{ data: [], error: null, loading: false }];
    returnsRef.i = 0;
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
  });

  it("is a screen of its own, reached from the queue", () => {
    render(<InvoicesPage />);
    expect(screen.getByRole("link", { name: /record an invoice/i })).toHaveAttribute(
      "href",
      "/invoices/new"
    );
  });

  it("commits from the header, with Cancel beside it and no back-link", () => {
    render(<NewInvoicePage />);
    expect(screen.getByRole("form", { name: /record an invoice/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /record invoice/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/invoices");
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });

  it("swaps the purchase order for a description when the buy was direct", () => {
    render(<NewInvoicePage />);
    const form = screen.getByRole("form", { name: /record an invoice/i });
    expect(form.querySelector('input[name="purchaseOrderId"]')).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText(/direct, with no purchase order/i));
    expect(form.querySelector('input[name="purchaseOrderId"]')).not.toBeInTheDocument();
    expect(form.querySelector('input[name="description"]')).toBeInTheDocument();
  });

  it("carries a duplicate number back to the queue as a warning that stands", () => {
    // A confirmation clears itself; a warning has something left in it for the reader to do, so the
    // duplicate travels as its own flag and the notice it raises does not fade.
    paramsRef.current = new URLSearchParams("recorded=INV-1&duplicate=1");
    render(<InvoicesPage />);
    expect(screen.getByText(/Invoice INV-1 was recorded\./i)).toBeInTheDocument();
    expect(screen.getByText(/already uses that number/i)).toBeInTheDocument();
  });
});
