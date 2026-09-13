import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { api } from "@/lib/api";
import type { ApiError, PayableView } from "@/lib/api";

const { authRef, queryRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queryRef: { current: { data: [] as PayableView[] | null, error: null as ApiError | null, loading: false } },
  reloadMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => ({ ...queryRef.current, reload: reloadMock }),
}));

import PayablesPage from "@/app/money/page";

function payable(o: Partial<PayableView>): PayableView {
  return {
    invoiceId: "inv1", invoiceNumber: "INV-1", vendorName: "Govind Wholesale",
    amount: 1000, paidToDate: 400, outstanding: 600, dueDate: "2026-01-31",
    agingBucket: "OVERDUE_31_PLUS", ...o,
  };
}

describe("payables", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queryRef.current = { data: [payable({})], error: null, loading: false };
    reloadMock.mockReset();
  });

  it("lists outstanding invoices with aging and a record-payment action", () => {
    render(<PayablesPage />);
    expect(screen.getByRole("heading", { name: /payments/i })).toBeInTheDocument();
    expect(screen.getByText("INV-1")).toBeInTheDocument();
    expect(screen.getAllByText("₹600").length).toBeGreaterThan(0); // row + total both show it
    expect(screen.getByText(/31\+ days overdue/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /record payment/i })).toBeInTheDocument();
  });

  it("shows an all-clear when nothing is outstanding", () => {
    queryRef.current = { data: [], error: null, loading: false };
    render(<PayablesPage />);
    expect(screen.getByText(/nothing outstanding/i)).toBeInTheDocument();
  });

  it("refuses a non-admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<PayablesPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });

  it("names a cleared date and amount when Save is pressed, then a negative amount, and records nothing (T-162)", async () => {
    const record = vi.spyOn(api, "recordInvoicePayment").mockResolvedValue(undefined as never);
    try {
      render(<PayablesPage />);
      fireEvent.click(screen.getByRole("button", { name: /record payment/i }));
      const form = screen.getByRole("form", { name: /record payment for INV-1/i });

      // Both boxes open pre-filled (today, and what is outstanding), so blank means cleared.
      fireEvent.change(within(form).getByLabelText("Date"), { target: { value: "" } });
      fireEvent.change(within(form).getByLabelText("Amount (₹)"), { target: { value: "" } });
      await act(async () => {
        fireEvent.click(within(form).getByRole("button", { name: /^save$/i }));
      });
      expect(within(form).getByText("Date is required")).toBeInTheDocument();
      expect(within(form).getByText("Amount (₹) is required")).toBeInTheDocument();
      expect(record).not.toHaveBeenCalled();

      fireEvent.change(within(form).getByLabelText("Date"), { target: { value: "2026-09-01" } });
      fireEvent.change(within(form).getByLabelText("Amount (₹)"), { target: { value: "-100" } });
      await act(async () => {
        fireEvent.click(within(form).getByRole("button", { name: /^save$/i }));
      });
      expect(within(form).getByText("Amount (₹) must be at least 0")).toBeInTheDocument();
      expect(within(form).queryByText("Date is required")).not.toBeInTheDocument();
      expect(record).not.toHaveBeenCalled();
    } finally {
      record.mockRestore();
    }
  });
});
