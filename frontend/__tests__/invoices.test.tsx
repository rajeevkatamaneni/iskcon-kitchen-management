import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
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

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
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
