import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { InvoicePaymentView, VendorInvoiceView } from "@/lib/api";

// The page issues one useAuthedQuery for the invoice, and a second for the payments — but only for
// a reader who holds MANAGE_VENDOR_PAYMENTS, because the payments section is a separate component
// that never mounts for anyone else. The mock returns by call index modulo length, so it maps
// correctly whether one query runs or two.
const { authRef, returnsRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: { current: [] as Array<{ data: unknown; error: null; loading: boolean }>, i: 0 },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => ({ id: "inv1" }),
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

import InvoiceDetailPage from "@/app/invoices/[id]/page";

const INVOICE: VendorInvoiceView = {
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
  dueDate: "2026-08-31",
  scanRef: "drive://invoices/INV-1.pdf",
  status: "PENDING",
  expectedValue: 1350,
  variance: 50,
  overdue: false,
  createdAt: "2026-08-01T00:00:00Z",
};

const PAYMENTS: InvoicePaymentView[] = [
  {
    id: "pay1",
    paidOn: "2026-08-10",
    amount: 900,
    method: "BANK_TRANSFER",
    reference: "NEFT-77",
    note: null,
    recordedByName: "Temple Admin",
    createdAt: "2026-08-10T00:00:00Z",
  },
];

function withReturns(...values: unknown[]) {
  returnsRef.current = values.map((data) => ({ data, error: null, loading: false }));
  returnsRef.i = 0;
}

describe("invoice detail", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    withReturns(INVOICE, PAYMENTS);
    reloadMock.mockReset();
  });

  it("shows what the list cannot: the dates, the scan, and the variance with something to be a delta from", () => {
    render(<InvoiceDetailPage />);
    expect(screen.getByRole("heading", { name: "INV-1" })).toBeInTheDocument();
    // Written out, not the stored ISO string, like every other date the app prints.
    expect(screen.getByText("1 Aug 2026")).toBeInTheDocument();
    expect(screen.getByText("drive://invoices/INV-1.pdf")).toBeInTheDocument();
    // The invoiced amount and the value of what was received, side by side.
    expect(screen.getByText("₹1,350")).toBeInTheDocument();
    expect(screen.getByText(/₹50 more than expected/i)).toBeInTheDocument();
    // And a way through to the order it was raised against.
    expect(screen.getByRole("link", { name: "PO-2026-0042" })).toHaveAttribute("href", "/orders/po1");
  });

  it("shows a Temple Admin what has been paid and what is still owed", () => {
    render(<InvoiceDetailPage />);
    expect(screen.getByRole("heading", { name: /payments/i })).toBeInTheDocument();
    // Twice over: once as the paid-to-date total, once as the single payment that makes it up.
    expect(screen.getAllByText("₹900")).toHaveLength(2);
    expect(screen.getByText("₹500")).toBeInTheDocument(); // outstanding
    expect(screen.getByText("NEFT-77")).toBeInTheDocument();
  });

  it("hides the payments section from a reader without the payments permission", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "me" } };
    withReturns(INVOICE, PAYMENTS);
    render(<InvoiceDetailPage />);
    // Absent, not zeroed: a manager who cannot see payments must not be told the invoice is unpaid.
    expect(screen.queryByRole("heading", { name: /payments/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/paid to date/i)).not.toBeInTheDocument();
  });

  it("renders a direct invoice's description, which is the only account of what was bought", () => {
    withReturns(
      {
        ...INVOICE,
        direct: true,
        purchaseOrderId: null,
        poNumber: null,
        description: "Cash market vegetables",
        expectedValue: null,
        variance: null,
      },
      []
    );
    render(<InvoiceDetailPage />);
    expect(screen.getByText("Cash market vegetables")).toBeInTheDocument();
    expect(screen.getByText(/direct — no purchase order/i)).toBeInTheDocument();
  });
});
