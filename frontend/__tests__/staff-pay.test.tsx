import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type {
  ApiError,
  JobTitleOption,
  StaffAdvanceView,
  StaffPaymentView,
  StaffPayView,
  StaffProfileView,
  StaffRegisterView,
  UserSummary,
} from "@/lib/api";

/**
 * Salary, advances and docking on the staff member's own record (B8).
 *
 * <p>What is worth asserting here is what the screen refuses to invent. A record with no salary says
 * so in words rather than showing zero; the termination screen states the advance balance and puts
 * the last payment beside the leaving date without drawing a conclusion from the two; and a
 * deduction typed against an advance travels with the payment it came from, because they are one act
 * at the desk.
 */

const {
  authRef,
  registerRef,
  titlesRef,
  devoteesRef,
  payRef,
  reloadMock,
  endMock,
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
  registerRef: {
    current: { data: null as StaffRegisterView | null, error: null as ApiError | null, loading: false },
  },
  titlesRef: { current: { data: [] as JobTitleOption[], error: null, loading: false } },
  devoteesRef: { current: { data: [] as UserSummary[], error: null, loading: false } },
  payRef: {
    current: { data: null as StaffPayView | null, error: null as ApiError | null, loading: false },
  },
  reloadMock: vi.fn(),
  endMock: vi.fn(),
  paymentMock: vi.fn(),
  advanceMock: vi.fn(),
  voidPaymentMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const source = fn.toString();
    const ref = source.includes("staffRegister")
      ? registerRef
      : source.includes("jobTitles")
        ? titlesRef
        : source.includes("staffPay")
          ? payRef
          : devoteesRef;
    return { ...ref.current, reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      endEmployment: endMock,
      recordStaffPayment: paymentMock,
      recordStaffAdvance: advanceMock,
      voidStaffPayment: voidPaymentMock,
    },
  };
});

import StaffPage from "@/app/staff/page";

function member(o: Partial<StaffProfileView> = {}): StaffProfileView {
  return {
    id: "s1",
    userId: "u1",
    fullName: "Gopal Das",
    phone: "+919876500001",
    email: "gopal@example.com",
    jobTitle: "HEAD_COOK",
    jobTitleOther: null,
    jobTitleLabel: "Head Cook",
    employmentType: "FULL_TIME",
    dateOfJoining: "2026-02-01",
    dateOfBirth: null,
    address: null,
    emergencyContactName: null,
    emergencyContactRelationship: null,
    emergencyContactPhone: null,
    panLast4: null,
    systemAccess: "KITCHEN_STAFF",
    employmentStatus: "ACTIVE",
    lastWorkingDay: null,
    endReason: null,
    notes: null,
    createdAt: "2026-02-01T00:00:00Z",
    ...o,
  };
}

function payment(o: Partial<StaffPaymentView> = {}): StaffPaymentView {
  return {
    id: "p1",
    paidOn: "2026-07-31",
    gross: 18000,
    deducted: 0,
    net: 18000,
    mode: "CHEQUE",
    modeLabel: "Cheque",
    reference: "114523",
    purpose: "SALARY",
    purposeLabel: "Salary",
    note: null,
    recordedByName: "Temple Admin",
    voidedAt: null,
    deductions: [],
    ...o,
  };
}

function advance(o: Partial<StaffAdvanceView> = {}): StaffAdvanceView {
  return {
    id: "a1",
    paidOn: "2026-06-10",
    amount: 5000,
    recovered: 2000,
    outstanding: 3000,
    mode: "CASH",
    modeLabel: "Cash",
    reference: null,
    note: null,
    recordedByName: "Temple Admin",
    voidedAt: null,
    ...o,
  };
}

function pay(o: Partial<StaffPayView> = {}): StaffPayView {
  return {
    staffId: "s1",
    fullName: "Gopal Das",
    currency: "INR",
    monthlySalary: 18000,
    advanceBalance: 3000,
    lastSalaryPayment: payment(),
    payments: [payment()],
    advances: [advance()],
    ...o,
  };
}

describe("staff pay", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    registerRef.current = { data: { current: [member()], former: [] }, error: null, loading: false };
    titlesRef.current = { data: [], error: null, loading: false };
    devoteesRef.current = { data: [], error: null, loading: false };
    payRef.current = { data: pay(), error: null, loading: false };
    reloadMock.mockReset();
    endMock.mockReset().mockResolvedValue(undefined);
    paymentMock.mockReset().mockResolvedValue({ id: "new" });
    advanceMock.mockReset().mockResolvedValue({ id: "new" });
    voidPaymentMock.mockReset().mockResolvedValue(undefined);
  });

  function openPay() {
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: "Pay" }));
    return screen.getByRole("region", { name: /gopal das’s pay/i });
  }

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

  it("shows a struck-out payment rather than hiding it", () => {
    payRef.current = {
      data: pay({ payments: [payment({ voidedAt: "2026-08-01T09:00:00Z" })] }),
      error: null,
      loading: false,
    };
    const panel = openPay();
    expect(within(panel).getByText(/struck out/i)).toBeInTheDocument();
  });

  it("is offered on a former staff member too, so a settlement can still be recorded", () => {
    registerRef.current = {
      data: {
        current: [],
        former: [member({ employmentStatus: "RESIGNED", lastWorkingDay: "2026-07-31" })],
      },
      error: null,
      loading: false,
    };
    render(<StaffPage />);
    expect(screen.getByRole("button", { name: "Pay" })).toBeInTheDocument();
  });
});

describe("the termination screen", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    registerRef.current = { data: { current: [member()], former: [] }, error: null, loading: false };
    titlesRef.current = { data: [], error: null, loading: false };
    devoteesRef.current = { data: [], error: null, loading: false };
    payRef.current = { data: pay(), error: null, loading: false };
    reloadMock.mockReset();
    endMock.mockReset().mockResolvedValue(undefined);
    paymentMock.mockReset().mockResolvedValue({ id: "new" });
  });

  function openTerminate() {
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: "Terminate" }));
    return screen.getByRole("form", { name: /terminate employment/i });
  }

  it("states the advance balance, which is arithmetic, and never guesses at salary owed", () => {
    openTerminate();
    const panel = screen.getByRole("region", { name: /terminate gopal das/i });
    expect(within(panel).getByText(/cash advances outstanding/i)).toBeInTheDocument();
    expect(within(panel).getByText(/3,000/)).toBeInTheDocument();
    expect(within(panel).getByText(/last salary payment/i)).toBeInTheDocument();
    // The figure and the day it was paid, side by side. The day's wording is the reader's locale's,
    // so it is matched by its number rather than by an English month order.
    expect(within(panel).getByText(/18,000 on .*31/)).toBeInTheDocument();
  });

  it("puts the last payment beside the leaving date and leaves the conclusion to the admin", () => {
    const form = openTerminate();
    fireEvent.change(form.querySelector('input[name="lastWorkingDay"]')!, {
      target: { value: "2026-09-12" },
    });
    expect(
      screen.getByText(/last recorded payment .*31.*; terminating .*12.*\./i)
    ).toBeInTheDocument();
  });

  it("says so plainly when nobody ever agreed a salary", () => {
    payRef.current = {
      data: pay({ monthlySalary: null, lastSalaryPayment: null, payments: [] }),
      error: null,
      loading: false,
    };
    openTerminate();
    const panel = screen.getByRole("region", { name: /terminate gopal das/i });
    expect(within(panel).getByText(/no salary recorded/i)).toBeInTheDocument();
    expect(within(panel).getByText(/none recorded/i)).toBeInTheDocument();
  });

  it("explains what happens to the record in words a temple would use", () => {
    openTerminate();
    expect(screen.getByText(/not removing anyone/i)).toBeInTheDocument();
    expect(screen.getByText(/moves to Former staff/i)).toBeInTheDocument();
    expect(screen.getByText(/stays on the record/i)).toBeInTheDocument();
  });

  it("records the settlement the admin typed as a payment, before the employment ends", async () => {
    const form = openTerminate();
    fireEvent.change(form.querySelector('input[name="lastWorkingDay"]')!, {
      target: { value: "2026-09-12" },
    });
    fireEvent.change(form.querySelector('input[name="settlementAmount"]')!, {
      target: { value: "22500" },
    });
    fireEvent.submit(form);

    await waitFor(() => expect(endMock).toHaveBeenCalled());
    expect(paymentMock).toHaveBeenCalled();
    expect(paymentMock.mock.calls[0][1]).toMatchObject({
      paidOn: "2026-09-12",
      amount: 22500,
      mode: "CASH",
      purpose: "SETTLEMENT",
    });
    expect(paymentMock.mock.invocationCallOrder[0]).toBeLessThan(endMock.mock.invocationCallOrder[0]);
  });

  it("ends the employment without a payment when there is nothing to settle", async () => {
    const form = openTerminate();
    fireEvent.change(form.querySelector('input[name="lastWorkingDay"]')!, {
      target: { value: "2026-09-12" },
    });
    fireEvent.submit(form);

    await waitFor(() => expect(endMock).toHaveBeenCalled());
    expect(paymentMock).not.toHaveBeenCalled();
  });
});
