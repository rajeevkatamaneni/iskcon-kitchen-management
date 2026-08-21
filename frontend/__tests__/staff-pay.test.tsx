import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { ApiError, type StaffPayView, type StaffRegisterView } from "@/lib/api";
import { advance, member, pay, payment } from "./staff-fixtures";

/**
 * Salary, advances and docking (B8), on the page one person's pay now has to itself.
 *
 * <p>What is worth asserting here is what the screen refuses to invent. A record with no salary says
 * so in words rather than showing zero; the termination screen states the advance balance and puts
 * the last payment beside the leaving date without drawing a conclusion from the two; and a
 * deduction typed against an advance travels with the payment it came from, because they are one act
 * at the desk.
 *
 * <p>Since 2026-08-20 the second thing worth asserting is what the pay page does <i>not</i> carry.
 * It was a panel above the register and is now a page, so hiring, the register itself and the Close
 * button all had to go. The back-link went with them on 2026-08-21: every screen of this kind is
 * left the same way, by the Cancel in its header.
 */

const {
  authRef,
  paramsRef,
  registerRef,
  payRef,
  reloadMock,
  paymentMock,
  advanceMock,
  voidPaymentMock,
} = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  paramsRef: { current: { id: "s1" } },
  registerRef: {
    current: { data: null as StaffRegisterView | null, error: null as ApiError | null, loading: false },
  },
  payRef: {
    current: { data: null as StaffPayView | null, error: null as ApiError | null, loading: false },
  },
  reloadMock: vi.fn(),
  paymentMock: vi.fn(),
  advanceMock: vi.fn(),
  voidPaymentMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const source = fn.toString();
    const ref = source.includes("staffRegister") ? registerRef : payRef;
    return { ...ref.current, reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      recordStaffPayment: paymentMock,
      recordStaffAdvance: advanceMock,
      voidStaffPayment: voidPaymentMock,
    },
  };
});

import StaffPayPage from "@/app/staff/[id]/pay/page";

describe("the pay page", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    paramsRef.current = { id: "s1" };
    registerRef.current = { data: { current: [member()], former: [] }, error: null, loading: false };
    payRef.current = { data: pay(), error: null, loading: false };
    reloadMock.mockReset();
    paymentMock.mockReset().mockResolvedValue({ id: "new" });
    advanceMock.mockReset().mockResolvedValue({ id: "new" });
    voidPaymentMock.mockReset().mockResolvedValue(undefined);
  });

  function openPay() {
    render(<StaffPayPage />);
    return screen.getByRole("region", { name: /gopal das’s pay/i });
  }

  it("is headed Pay, and says whose pay it is", () => {
    render(<StaffPayPage />);
    expect(screen.getByRole("heading", { name: "Pay" })).toBeInTheDocument();
    // A page headed only "Pay" would not say which of the temple's staff is about to be paid.
    expect(screen.getByText(/Gopal Das · Head Cook/)).toBeInTheDocument();
  });

  it("is left by the Cancel in its header, and by nothing else", () => {
    render(<StaffPayPage />);
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/staff");
    // The arrow in the corner went on 2026-08-21. Two ways out is two answers to one question.
    expect(screen.queryByRole("link", { name: /back to staff/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
    // And the three things that came with the panel when it lived on the register, and should not
    // have followed it here: there is nobody to hire on this screen, nobody else to look at, and
    // nothing to close.
    expect(screen.queryByRole("link", { name: /hire someone/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: /current staff/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^close$/i })).not.toBeInTheDocument();
  });

  it("shows the salary, the advance balance and the last payment in the temple's currency", () => {
    const panel = openPay();
    expect(within(panel).getAllByText(/18,000/).length).toBeGreaterThan(0);
    expect(within(panel).getAllByText(/3,000/).length).toBeGreaterThan(0);
    // A rupee sign is the temple's, not the code's: the currency comes back on the record.
    expect(within(panel).getAllByText(/₹/).length).toBeGreaterThan(0);
  });

  it("says there is no salary recorded rather than showing a wage of nothing", () => {
    payRef.current = { data: pay({ monthlySalary: null }), error: null, loading: false };
    const panel = openPay();
    expect(within(panel).getByText(/no salary recorded/i)).toBeInTheDocument();
    expect(within(panel).queryByText("₹0")).not.toBeInTheDocument();
  });

  it("sends a payment with the advance it docks, as one act", async () => {
    const panel = openPay();
    const form = within(panel).getByRole("form", { name: /record a payment/i });

    fireEvent.change(form.querySelector('input[name="paidOn"]')!, { target: { value: "2026-08-31" } });
    fireEvent.change(form.querySelector('input[name="amount"]')!, { target: { value: "18000" } });
    fireEvent.change(form.querySelector('select[name="mode"]')!, { target: { value: "CHEQUE" } });
    fireEvent.change(form.querySelector('input[name="reference"]')!, { target: { value: "114524" } });
    fireEvent.change(form.querySelector('input[name="deduct-a1"]')!, { target: { value: "1500" } });
    fireEvent.submit(form);

    await waitFor(() => expect(paymentMock).toHaveBeenCalled());
    const [id, input] = paymentMock.mock.calls[0];
    expect(id).toBe("s1");
    expect(input).toMatchObject({
      paidOn: "2026-08-31",
      amount: 18000,
      mode: "CHEQUE",
      reference: "114524",
      purpose: "SALARY",
    });
    expect(input.deductions).toEqual([{ advanceId: "a1", amount: 1500 }]);
  });

  it("treats a blank box against an advance as recovering nothing, not as a deduction of zero", async () => {
    const panel = openPay();
    const form = within(panel).getByRole("form", { name: /record a payment/i });

    fireEvent.change(form.querySelector('input[name="paidOn"]')!, { target: { value: "2026-08-31" } });
    fireEvent.change(form.querySelector('input[name="amount"]')!, { target: { value: "18000" } });
    fireEvent.submit(form);

    await waitFor(() => expect(paymentMock).toHaveBeenCalled());
    expect(paymentMock.mock.calls[0][1].deductions).toEqual([]);
  });

  it("asks for a cheque number only when the payment is a cheque", () => {
    const panel = openPay();
    const form = within(panel).getByRole("form", { name: /record a payment/i });
    const reference = () => form.querySelector('input[name="reference"]') as HTMLInputElement;

    expect(reference().required).toBe(false);
    fireEvent.change(form.querySelector('select[name="mode"]')!, { target: { value: "CHEQUE" } });
    expect(reference().required).toBe(true);
  });

  it("records an advance, and never offers payroll as a way of giving one", async () => {
    const panel = openPay();
    const form = within(panel).getByRole("form", { name: /record an advance/i });
    const modes = form.querySelector('select[name="advanceMode"]') as HTMLSelectElement;
    expect([...modes.options].map((o) => o.value)).toEqual(["CASH", "CHEQUE"]);

    fireEvent.change(form.querySelector('input[name="advancePaidOn"]')!, { target: { value: "2026-08-02" } });
    fireEvent.change(form.querySelector('input[name="advanceAmount"]')!, { target: { value: "2500" } });
    fireEvent.submit(form);

    await waitFor(() => expect(advanceMock).toHaveBeenCalled());
    expect(advanceMock.mock.calls[0][1]).toMatchObject({
      paidOn: "2026-08-02",
      amount: 2500,
      mode: "CASH",
    });
  });

  it("offers to strike out a payment nothing was docked from, and not one that was", async () => {
    payRef.current = {
      data: pay({
        payments: [payment({ id: "p1" }), payment({ id: "p2", deducted: 2000, net: 16000 })],
      }),
      error: null,
      loading: false,
    };
    const panel = openPay();
    // One button, for the payment that recovered nothing — the advance in this fixture has been
    // part-recovered, so it offers none either. Striking a payment that had docked something would
    // hand the balance back silently, which the API refuses (KMS-4961).
    expect(within(panel).getAllByRole("button", { name: /strike out/i })).toHaveLength(1);

    fireEvent.click(within(panel).getByRole("button", { name: /strike out/i }));
    await waitFor(() => expect(voidPaymentMock).toHaveBeenCalledWith("s1", "p1", "test-token"));
  });

  it("draws a struck payment struck, and still says so to a screen reader", () => {
    payRef.current = {
      data: pay({ payments: [payment({ voidedAt: "2026-08-01T09:00:00Z" })] }),
      error: null,
      loading: false,
    };
    const panel = openPay();
    // The label beside the purpose went on 2026-08-21: a line through a row of figures says it at a
    // glance, where a word had to be found and read. Nothing is hidden — the row is still there.
    const row = within(panel).getByText("Salary").closest("tr")!;
    expect(row).toHaveClass("line-through", "text-ink-muted");
    expect(within(row).getByText(/struck out/i)).toHaveClass("sr-only");
    expect(within(row).queryByRole("button", { name: /strike out/i })).not.toBeInTheDocument();
  });

  it("draws a struck advance the same way", () => {
    payRef.current = {
      data: pay({ advances: [advance({ voidedAt: "2026-08-01T09:00:00Z", recovered: 0 })] }),
      error: null,
      loading: false,
    };
    const panel = openPay();
    const table = within(panel).getByRole("region", { name: /advances/i });
    const row = within(table).getAllByRole("row")[1];
    expect(row).toHaveClass("line-through", "text-ink-muted");
    expect(within(row).getByText(/struck out/i)).toHaveClass("sr-only");
  });

  it("opens for a former staff member too, and records what they are paid as a settlement", async () => {
    registerRef.current = {
      data: {
        current: [],
        former: [
          { profile: member({ employmentStatus: "RESIGNED", lastWorkingDay: "2026-07-31" }), banned: false },
        ],
      },
      error: null,
      loading: false,
    };
    const panel = openPay();
    // The one line under the title says who, what they were called, and that they have left.
    expect(screen.getByText(/Gopal Das · Head Cook · left/)).toBeInTheDocument();

    const form = within(panel).getByRole("form", { name: /record a payment/i });
    fireEvent.change(form.querySelector('input[name="paidOn"]')!, { target: { value: "2026-08-05" } });
    fireEvent.change(form.querySelector('input[name="amount"]')!, { target: { value: "9000" } });
    fireEvent.submit(form);

    // Nobody who has left is being paid for a month's work, so it is the settlement by elimination.
    await waitFor(() => expect(paymentMock).toHaveBeenCalled());
    expect(paymentMock.mock.calls[0][1]).toMatchObject({ purpose: "SETTLEMENT" });
  });

  it("says so plainly when the address belongs to nobody on the register", () => {
    paramsRef.current = { id: "gone" };
    render(<StaffPayPage />);
    expect(screen.getByText(/can’t find that person/i)).toBeInTheDocument();
    expect(screen.queryByRole("form", { name: /record a payment/i })).not.toBeInTheDocument();
  });

  it("shows a failed load as the error it was, with its code", () => {
    payRef.current = {
      data: null,
      error: new ApiError({
        code: "KMS-4960",
        message: "We couldn't load this.",
        action: "Try again.",
        fieldErrors: [],
      }),
      loading: false,
    };
    render(<StaffPayPage />);
    expect(screen.getByRole("alert")).toHaveTextContent("KMS-4960");
    expect(screen.queryByRole("form", { name: /record a payment/i })).not.toBeInTheDocument();
  });
});
