import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { api } from "@/lib/api";
import type { PayableView, VendorInvoiceView } from "@/lib/api";
import { todayIso } from "@/lib/format";

/**
 * The Invoices list's filters and total owed (R-INV-8, T-275).
 *
 * <p>Unlike invoices.test.tsx this leaves `useAuthedQuery` real and stubs the two API calls under
 * it, because what is being tested is partly *which* call a filter makes: a payer's money filters
 * read `/api/v1/payables`, and a Kitchen Manager must never make that call at all, since the server
 * refuses it to them.
 *
 * <p>The dates are built from the temple's today at the moment the test runs, rather than from a
 * frozen clock, so every boundary is exact whatever day CI runs on: due today, due in 7 days and in
 * 8, and 1, 30 and 31 days overdue.
 */

const { authRef, paramsRef, replaceMock, getToken } = vi.hoisted(() => ({
  authRef: { current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } },
  paramsRef: { current: new URLSearchParams() },
  replaceMock: vi.fn(),
  // Stable across renders: useAuthedQuery re-fetches when getToken changes identity.
  getToken: async () => "test-token",
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({}),
  usePathname: () => "/invoices",
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken }),
}));

import InvoicesPage from "@/app/invoices/page";

/** An ISO date `days` after the temple's today (negative for before). */
function fromToday(days: number): string {
  const [y, m, d] = todayIso().split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

function invoice(id: string, dueDate: string | null, o: Partial<VendorInvoiceView> = {}): VendorInvoiceView {
  return {
    id,
    vendorId: "v1",
    vendorName: "Govind Wholesale",
    purchaseOrderId: null,
    poNumber: null,
    direct: true,
    description: null,
    invoiceNumber: id,
    invoiceDate: fromToday(-40),
    amount: 1000,
    dueDate,
    scanRef: null,
    status: "PENDING",
    expectedValue: null,
    variance: null,
    overdue: dueDate != null && dueDate < todayIso(),
    voidedAt: null,
    voidReason: null,
    creditedAmount: 0,
    createdAt: "2026-08-01T00:00:00Z",
    ...o,
  };
}

/** The boundary fixtures. The invoice number says which boundary each one sits on. */
const DUE = {
  "DUE-TODAY": 0,
  "DUE-IN-7": 7,
  "DUE-IN-8": 8,
  "LATE-1": -1,
  "LATE-30": -30,
  "LATE-31": -31,
} as const;

const OWED_INVOICES: VendorInvoiceView[] = [
  ...Object.entries(DUE).map(([id, days]) => invoice(id, fromToday(days))),
  invoice("NO-DUE-DATE", null),
];
const PAID = invoice("SETTLED", fromToday(-5), { status: "PAID", overdue: false });
const VOIDED = invoice("STRUCK", fromToday(-5), { status: "VOIDED", overdue: false });
// Still says PENDING though its payments cover it: a bill paid off before the status was restated.
// It is what `status=PENDING` would wrongly call Unpaid and `owed=true` leaves out (T-281).
const PAID_OFF_PENDING = invoice("PAID-OFF-PENDING", fromToday(-3));
const ALL_INVOICES = [...OWED_INVOICES, PAID, VOIDED, PAID_OFF_PENDING];
const OWED_IDS = new Set(OWED_INVOICES.map((i) => i.id));

// Each owed invoice has a different outstanding figure, so the total proves every one was added:
// 100 + 200 + ... + 700 = 2,800.
const PAYABLES: PayableView[] = OWED_INVOICES.map((inv, i) => ({
  invoiceId: inv.id,
  invoiceNumber: inv.invoiceNumber,
  vendorName: inv.vendorName,
  amount: 1000,
  paidToDate: 1000 - (i + 1) * 100,
  outstanding: (i + 1) * 100,
  dueDate: inv.dueDate,
  agingBucket: "CURRENT",
}));

beforeEach(() => {
  authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
  paramsRef.current = new URLSearchParams();
  replaceMock.mockReset();
  // The server's own answer to each query the page can make. `owed` and `overdue` both require money
  // still owed, as the server does since T-281; `status` is the status column and nothing more.
  vi.spyOn(api, "listInvoices").mockImplementation(async (filters = {}) => {
    if (filters.status) return ALL_INVOICES.filter((i) => i.status === filters.status);
    if (filters.owed) return ALL_INVOICES.filter((i) => OWED_IDS.has(i.id));
    if (filters.overdue) return ALL_INVOICES.filter((i) => i.overdue && OWED_IDS.has(i.id));
    return ALL_INVOICES;
  });
  vi.spyOn(api, "payables").mockResolvedValue(PAYABLES);
});

afterEach(() => {
  vi.restoreAllMocks();
});

/** The invoice numbers in the table, in order, once it has loaded. */
async function rowsShown(): Promise<string[]> {
  const table = await screen.findByRole("table");
  return within(table)
    .getAllByRole("link")
    .map((a) => a.textContent ?? "");
}

function asAdminWith(filter: string | null) {
  paramsRef.current = new URLSearchParams(filter ? `filter=${filter}` : "");
  render(<InvoicesPage />);
}

describe("the Invoices list for a Temple Admin (MANAGE_VENDOR_PAYMENTS)", () => {
  it("offers the four money filters beside All, Paid and Voided, and the total owed", async () => {
    asAdminWith(null);
    const tabs = screen.getByRole("tablist", { name: "Filter invoices" });
    expect(within(tabs).getAllByRole("tab").map((t) => t.textContent)).toEqual([
      "All",
      "Unpaid",
      "Due this week",
      "1–30 days overdue",
      "31+ days overdue",
      "Paid",
      "Voided",
    ]);
    expect(await screen.findByText("₹2,800")).toBeInTheDocument();
    expect(screen.getByTestId("total-owed")).toHaveTextContent("Total owed₹2,800");
  });

  it("shows every invoice under All", async () => {
    asAdminWith(null);
    expect(await rowsShown()).toEqual(ALL_INVOICES.map((i) => i.id));
  });

  it("selects Unpaid on load from ?filter=unpaid, where /money sends people", async () => {
    asAdminWith("unpaid");
    expect(screen.getByRole("tab", { name: "Unpaid" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("tab", { name: "All" })).toHaveAttribute("aria-selected", "false");
    expect(await rowsShown()).toEqual(OWED_INVOICES.map((i) => i.id));
  });

  it("asks the server for Unpaid as owed=true, the same question a Kitchen Manager's Unpaid asks", async () => {
    asAdminWith("unpaid");
    expect(await rowsShown()).not.toContain("PAID-OFF-PENDING");
    expect(api.listInvoices).toHaveBeenLastCalledWith({ owed: true }, "test-token");
  });

  it("counts due this week from today to seven days out, both ends in", async () => {
    asAdminWith("due-this-week");
    expect(await rowsShown()).toEqual(["DUE-TODAY", "DUE-IN-7"]);
  });

  it("counts 1–30 days overdue from the day after the due date to the thirtieth day", async () => {
    asAdminWith("overdue-1-30");
    expect(await rowsShown()).toEqual(["LATE-1", "LATE-30"]);
  });

  it("counts 31+ days overdue from the thirty-first day", async () => {
    asAdminWith("overdue-31");
    expect(await rowsShown()).toEqual(["LATE-31"]);
  });

  it("keeps the Paid and Voided views", async () => {
    asAdminWith("paid");
    expect(await rowsShown()).toEqual(["SETTLED"]);
  });

  it("keeps the total owed as everything owed, whichever filter is on", async () => {
    asAdminWith("overdue-31");
    await rowsShown();
    expect(screen.getByTestId("total-owed")).toHaveTextContent("₹2,800");
  });

  it("puts the chosen filter in the address, and All clears it", async () => {
    asAdminWith(null);
    await rowsShown();
    fireEvent.click(screen.getByRole("tab", { name: "1–30 days overdue" }));
    expect(replaceMock).toHaveBeenLastCalledWith("/invoices?filter=overdue-1-30");
    fireEvent.click(screen.getByRole("tab", { name: "All" }));
    expect(replaceMock).toHaveBeenLastCalledWith("/invoices");
  });

  it("names the filter when it matches nothing", async () => {
    vi.mocked(api.payables).mockResolvedValue(PAYABLES.filter((p) => p.invoiceId !== "LATE-31"));
    asAdminWith("overdue-31");
    expect(await screen.findByText("Nothing 31+ days overdue")).toBeInTheDocument();
  });
});

describe("the Invoices list for a Kitchen Manager", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "me" } };
  });

  it("offers All, Unpaid, Overdue, Paid and Voided, and no total owed", async () => {
    render(<InvoicesPage />);
    const tabs = screen.getByRole("tablist", { name: "Filter invoices" });
    expect(within(tabs).getAllByRole("tab").map((t) => t.textContent)).toEqual([
      "All",
      "Unpaid",
      "Overdue",
      "Paid",
      "Voided",
    ]);
    expect(await rowsShown()).toEqual(ALL_INVOICES.map((i) => i.id));
    expect(screen.queryByText(/total owed/i)).not.toBeInTheDocument();
    for (const money of ["Due this week", "1–30 days overdue", "31+ days overdue"]) {
      expect(screen.queryByRole("tab", { name: money })).not.toBeInTheDocument();
    }
  });

  it("never asks the server for what is owed, which it refuses them", async () => {
    render(<InvoicesPage />);
    await rowsShown();
    expect(api.payables).not.toHaveBeenCalled();
  });

  it("answers Unpaid as owed=true, the same rows a Temple Admin sees, and Overdue as overdue=true", async () => {
    paramsRef.current = new URLSearchParams("filter=unpaid");
    const { unmount } = render(<InvoicesPage />);
    expect(screen.getByRole("tab", { name: "Unpaid" })).toHaveAttribute("aria-selected", "true");
    // Not the PENDING bill whose payments already cover it, which status=PENDING would have shown.
    expect(await rowsShown()).toEqual(OWED_INVOICES.map((i) => i.id));
    expect(api.listInvoices).toHaveBeenLastCalledWith({ owed: true }, "test-token");
    unmount();

    paramsRef.current = new URLSearchParams("filter=overdue");
    render(<InvoicesPage />);
    expect(await rowsShown()).toEqual(["LATE-1", "LATE-30", "LATE-31"]);
    expect(api.listInvoices).toHaveBeenLastCalledWith({ overdue: true }, "test-token");
  });

  it("shows All for a payer's filter in the address, rather than a view they can't select", async () => {
    paramsRef.current = new URLSearchParams("filter=overdue-31");
    render(<InvoicesPage />);
    expect(screen.getByRole("tab", { name: "All" })).toHaveAttribute("aria-selected", "true");
    expect(await rowsShown()).toEqual(ALL_INVOICES.map((i) => i.id));
  });

  it("has the same Create an invoice button", async () => {
    render(<InvoicesPage />);
    await rowsShown();
    expect(screen.getByRole("link", { name: "Create an invoice" })).toHaveAttribute("href", "/invoices/new");
  });
});
