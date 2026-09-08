import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type { InvoicePaymentView, VendorInvoiceView } from "@/lib/api";

/**
 * Correcting a bill on the screens (T-010): a struck bill, a credited one, and a payment that has
 * been reversed.
 *
 * <p>What these assert is that the correction is <em>visible</em>, not merely accepted by the API.
 * A void that only exists in a JSON response is a bill that still reads as owed to the person
 * looking at it, which is the state this work exists to end.
 *
 * <p>The reversal assertions carry the shape of the ledger with them, because it is easy to write a
 * screen that hides the reversed payment. Nothing is deleted: both rows stay, and the screen has to
 * say which is which.
 */

const { authRef, invoiceRef, paymentsRef, voidMock, creditMock, reverseMock, reloadMock } = vi.hoisted(
  () => ({
    authRef: {
      current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
        status: string;
        appUser: { role: string; userId: string } | null;
      },
    },
    invoiceRef: { current: null as unknown },
    paymentsRef: { current: [] as unknown },
    voidMock: vi.fn(),
    creditMock: vi.fn(),
    reverseMock: vi.fn(),
    reloadMock: vi.fn(),
  })
);

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  useParams: () => ({ id: "inv1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// The page asks for the invoice first and the payments second, and the payments query only exists
// for a reader who holds the permission. Keying on the call's own source rather than on call order
// keeps that true whether one query runs or two.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const payments = fn.toString().includes("listInvoicePayments");
    return {
      data: payments ? paymentsRef.current : invoiceRef.current,
      error: null,
      loading: false,
      reload: reloadMock,
    };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      voidInvoice: voidMock,
      creditInvoice: creditMock,
      reverseInvoicePayment: reverseMock,
    },
  };
});

import InvoiceDetailPage from "@/app/invoices/[id]/page";
import InvoicesPage from "@/app/invoices/page";

function invoice(o: Partial<VendorInvoiceView> = {}): VendorInvoiceView {
  return {
    id: "inv1",
    vendorId: "v1",
    vendorName: "Govind Wholesale",
    purchaseOrderId: null,
    poNumber: null,
    direct: true,
    description: "Cash market vegetables",
    invoiceNumber: "INV-1",
    invoiceDate: "2026-08-01",
    amount: 1400,
    dueDate: "2026-08-31",
    scanRef: null,
    status: "PENDING",
    expectedValue: null,
    variance: null,
    overdue: false,
    voidedAt: null,
    voidReason: null,
    creditedAmount: 0,
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

function payment(o: Partial<InvoicePaymentView> = {}): InvoicePaymentView {
  return {
    id: "pay1",
    paidOn: "2026-08-10",
    amount: 900,
    method: "BANK_TRANSFER",
    reference: "NEFT-77",
    note: null,
    recordedByName: "Temple Admin",
    reverses: null,
    reversedBy: null,
    reverseReason: null,
    createdAt: "2026-08-10T00:00:00Z",
    ...o,
  };
}

beforeEach(() => {
  authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
  invoiceRef.current = invoice();
  paymentsRef.current = [];
  voidMock.mockReset().mockResolvedValue(undefined);
  creditMock.mockReset().mockResolvedValue(undefined);
  reverseMock.mockReset().mockResolvedValue(undefined);
  reloadMock.mockReset();
});

describe("a bill that was struck", () => {
  it("says so on the invoice, with the reason and not only a status", () => {
    invoiceRef.current = invoice({
      status: "VOIDED",
      voidedAt: "2026-09-02T06:30:00Z",
      voidReason: "Billed twice for the same delivery.",
    });
    render(<InvoiceDetailPage />);

    expect(screen.getByText("Voided")).toBeInTheDocument();
    expect(screen.getByText(/struck as never owed/i)).toBeInTheDocument();
    expect(screen.getByText(/Billed twice for the same delivery\./)).toBeInTheDocument();
  });

  it("offers neither correction again — a struck bill is the end of it", () => {
    invoiceRef.current = invoice({
      status: "VOIDED",
      voidedAt: "2026-09-02T06:30:00Z",
      voidReason: "Not our bill.",
    });
    render(<InvoiceDetailPage />);

    expect(screen.queryByRole("button", { name: /void this bill/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /record a credit note/i })).not.toBeInTheDocument();
  });

  it("carries the third status onto the queue, as a badge and as a filter", () => {
    invoiceRef.current = [
      invoice({ status: "VOIDED", voidedAt: "2026-09-02T06:30:00Z", voidReason: "Duplicate." }),
    ];
    render(<InvoicesPage />);

    // Scoped to the table, because the word is also the new filter option — which is the other
    // half of what this asserts.
    expect(within(screen.getByRole("table")).getByText("Voided")).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Voided" })).toBeInTheDocument();
  });
});

describe("voiding and crediting", () => {
  it("asks why before it strikes anything, and sends what was typed", async () => {
    render(<InvoiceDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /void this bill/i }));

    const panel = screen.getByRole("form", { name: /void this invoice/i });
    // Refused until there are words in the box, the way dropping a vendor already works.
    const commit = within(panel).getByRole("button", { name: /void this bill/i });
    expect(commit).toBeDisabled();

    fireEvent.change(within(panel).getByRole("textbox"), {
      target: { value: "Billed twice for the same delivery." },
    });
    expect(commit).toBeEnabled();
    fireEvent.click(commit);

    await waitFor(() =>
      expect(voidMock).toHaveBeenCalledWith(
        "inv1",
        "Billed twice for the same delivery.",
        "test-token"
      )
    );
  });

  it("takes an amount as well as a reason for a credit note", async () => {
    render(<InvoiceDetailPage />);
    fireEvent.click(screen.getByRole("button", { name: /record a credit note/i }));

    const panel = screen.getByRole("form", { name: /record a credit note/i });
    fireEvent.change(within(panel).getByRole("spinbutton"), { target: { value: "400" } });
    fireEvent.change(within(panel).getByRole("textbox"), { target: { value: "Short by two sacks." } });
    fireEvent.click(within(panel).getByRole("button", { name: /record the credit note/i }));

    await waitFor(() =>
      expect(creditMock).toHaveBeenCalledWith(
        "inv1",
        { amount: 400, reason: "Short by two sacks." },
        "test-token"
      )
    );
  });

  it("shows a credited bill as still payable, and says what is left owed", () => {
    invoiceRef.current = invoice({ creditedAmount: 400 });
    paymentsRef.current = [];
    render(<InvoiceDetailPage />);

    // The invoiced figure is what the vendor sent and does not change.
    expect(screen.getAllByText("₹1,400").length).toBeGreaterThan(0);
    expect(screen.getByText("₹400")).toBeInTheDocument();
    // Owed after the credit, and the same figure again as what is outstanding.
    expect(screen.getAllByText("₹1,000")).toHaveLength(2);
    // A credit is not a void: the bill is still in the cycle.
    expect(screen.queryByText(/struck as never owed/i)).not.toBeInTheDocument();
    expect(screen.getByText("Pending")).toBeInTheDocument();
  });

  it("hides all of it from a reader without the payments permission", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "me" } };
    render(<InvoiceDetailPage />);

    expect(screen.queryByRole("button", { name: /void this bill/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /record a credit note/i })).not.toBeInTheDocument();
  });
});

describe("reversing a payment", () => {
  it("asks what happened and sends the reason with it", async () => {
    paymentsRef.current = [payment()];
    render(<InvoiceDetailPage />);

    fireEvent.click(screen.getByRole("button", { name: "Reverse" }));
    const panel = screen.getByRole("form", { name: /reverse this payment/i });
    // Says what will actually be written, because nothing disappears from the ledger.
    expect(within(panel).getByText(/an entry of the opposite amount is added beside it/i))
      .toBeInTheDocument();

    fireEvent.change(within(panel).getByRole("textbox"), { target: { value: "The cheque bounced." } });
    fireEvent.click(within(panel).getByRole("button", { name: /reverse this payment/i }));

    await waitFor(() =>
      expect(reverseMock).toHaveBeenCalledWith("inv1", "pay1", "The cheque bounced.", "test-token")
    );
  });

  it("keeps both rows and says which is which", () => {
    paymentsRef.current = [
      payment({ id: "pay1", reversedBy: "rev1" }),
      payment({
        id: "rev1",
        amount: -900,
        reverses: "pay1",
        reverseReason: "The cheque bounced.",
        reference: "NEFT-77",
      }),
    ];
    render(<InvoiceDetailPage />);

    // Nothing is hidden: the payment and its correction both stand.
    expect(screen.getByText("-₹900")).toBeInTheDocument();
    expect(screen.getByText("Reversed")).toBeInTheDocument();
    expect(screen.getByText("The cheque bounced.")).toBeInTheDocument();
    // And between them they are worth nothing, which the total has to agree with.
    expect(screen.getByText("₹0")).toBeInTheDocument();
  });

  it("offers no reversal on a row that is already a correction", () => {
    paymentsRef.current = [
      payment({ id: "pay1", reversedBy: "rev1" }),
      payment({ id: "rev1", amount: -900, reverses: "pay1", reverseReason: "Bounced." }),
    ];
    render(<InvoiceDetailPage />);

    // Neither the struck payment nor the correction: one is done, the other is not a payment.
    expect(screen.queryByRole("button", { name: "Reverse" })).not.toBeInTheDocument();
  });
});
