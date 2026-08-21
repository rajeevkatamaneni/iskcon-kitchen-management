import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ApiError, BanCategoryOption, StaffPayView, StaffRegisterView } from "@/lib/api";
import { CATEGORIES, member, pay } from "./staff-fixtures";

/**
 * Ending somebody's employment (E6-S8), on the screen the whole focus-screen pattern was measured
 * on.
 *
 * <p>As a panel over the register there was no scroll position where the person's name and the
 * button that ends their employment were both on screen. That is what the first test here holds:
 * one commit, in a header that also says who this is about.
 *
 * <p>The rest is what the screen refuses to invent. The advance balance is arithmetic and is stated;
 * what is owed in salary is not, so the last payment and the leaving date are put side by side and
 * the conclusion is left to the person signing it off. And the settlement is recorded <b>before</b>
 * the employment ends, because money that changed hands is true whether or not the termination then
 * succeeds.
 */

const {
  authRef,
  paramsRef,
  registerRef,
  payRef,
  categoriesRef,
  pushMock,
  endMock,
  paymentMock,
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
  payRef: { current: { data: null as StaffPayView | null, error: null as ApiError | null, loading: false } },
  categoriesRef: { current: { data: [] as BanCategoryOption[], error: null, loading: false } },
  pushMock: vi.fn(),
  endMock: vi.fn(),
  paymentMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: pushMock }),
  useParams: () => paramsRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const source = fn.toString();
    const ref = source.includes("staffRegister")
      ? registerRef
      : source.includes("staffPay")
        ? payRef
        : categoriesRef;
    return { ...ref.current, reload: vi.fn() };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, endEmployment: endMock, recordStaffPayment: paymentMock },
  };
});

import TerminateStaffPage from "@/app/staff/[id]/terminate/page";

describe("terminating an employment", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    paramsRef.current = { id: "s1" };
    registerRef.current = { data: { current: [member()], former: [] }, error: null, loading: false };
    payRef.current = { data: pay(), error: null, loading: false };
    categoriesRef.current = { data: CATEGORIES, error: null, loading: false };
    pushMock.mockReset();
    endMock.mockReset().mockResolvedValue(undefined);
    paymentMock.mockReset().mockResolvedValue({ id: "new" });
  });

  function form() {
    render(<TerminateStaffPage />);
    return screen.getByRole("form", { name: /terminate employment/i });
  }

  it("keeps the name and the button that ends their employment on screen together", () => {
    render(<TerminateStaffPage />);
    expect(screen.getByRole("heading", { name: "Terminate employment" })).toBeInTheDocument();
    expect(screen.getByText(/Gopal Das · Head Cook · joined/)).toBeInTheDocument();

    // One commit, not two, and the way out beside it.
    expect(screen.getAllByRole("button", { name: "Terminate" })).toHaveLength(1);
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/staff");
  });

  it("says what happens to the record, in the words that were approved", () => {
    render(<TerminateStaffPage />);
    expect(
      screen.getByText("Their record and their work stay. Only their employment ends.")
    ).toBeInTheDocument();
  });

  it("states the advance balance, which is arithmetic, and never guesses at salary owed", () => {
    render(<TerminateStaffPage />);
    expect(screen.getByText(/cash advances outstanding/i)).toBeInTheDocument();
    expect(screen.getByText(/3,000/)).toBeInTheDocument();
    expect(screen.getByText(/last salary payment/i)).toBeInTheDocument();
    // The figure and the day it was paid, side by side. The day's wording is the reader's locale's,
    // so it is matched by its number rather than by an English month order.
    expect(screen.getByText(/18,000 on .*31/)).toBeInTheDocument();
  });

  it("puts the last payment beside the leaving date and leaves the conclusion to the admin", () => {
    const f = form();
    fireEvent.change(f.querySelector('input[name="lastWorkingDay"]')!, {
      target: { value: "2026-09-12" },
    });
    expect(screen.getByText(/last recorded payment .*31.*last working day .*12/i)).toBeInTheDocument();
    expect(screen.getByText(/yours to settle/i)).toBeInTheDocument();
  });

  it("says so plainly when nobody ever agreed a salary", () => {
    payRef.current = {
      data: pay({ monthlySalary: null, lastSalaryPayment: null, payments: [] }),
      error: null,
      loading: false,
    };
    render(<TerminateStaffPage />);
    expect(screen.getByText(/no salary recorded/i)).toBeInTheDocument();
    expect(screen.getByText(/none recorded/i)).toBeInTheDocument();
  });

  it("defaults to taking the sign-in away for a dismissal, but not a resignation", () => {
    const f = form();
    const revoke = () => f.querySelector('input[name="revokeSignIn"]') as HTMLInputElement;

    expect(revoke().checked).toBe(false);
    fireEvent.change(f.querySelector('select[name="status"]')!, { target: { value: "TERMINATED" } });
    expect(revoke().checked).toBe(true);
  });

  it("ends employment with the reason and the last working day, then goes back to the register", async () => {
    const f = form();
    fireEvent.change(f.querySelector('input[name="lastWorkingDay"]')!, { target: { value: "2026-06-30" } });
    fireEvent.change(f.querySelector('input[name="reason"]')!, { target: { value: "Moved to Mayapur" } });
    fireEvent.submit(f);

    await waitFor(() => expect(endMock).toHaveBeenCalled());
    expect(endMock.mock.calls[0][1]).toMatchObject({
      status: "RESIGNED",
      lastWorkingDay: "2026-06-30",
      reason: "Moved to Mayapur",
      revokeSignIn: false,
    });
    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/staff?terminated=Gopal%20Das"));
  });

  it("records the settlement the admin typed as a payment, before the employment ends", async () => {
    const f = form();
    fireEvent.change(f.querySelector('input[name="lastWorkingDay"]')!, { target: { value: "2026-09-12" } });
    fireEvent.change(f.querySelector('input[name="settlementAmount"]')!, { target: { value: "22500" } });
    fireEvent.submit(f);

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
    const f = form();
    fireEvent.change(f.querySelector('input[name="lastWorkingDay"]')!, { target: { value: "2026-09-12" } });
    fireEvent.submit(f);

    await waitFor(() => expect(endMock).toHaveBeenCalled());
    expect(paymentMock).not.toHaveBeenCalled();
  });

  it("refuses to offer a second termination of an employment that already ended", () => {
    registerRef.current = {
      data: {
        current: [],
        former: [{ profile: member({ employmentStatus: "RESIGNED", lastWorkingDay: "2026-06-30" }), banned: false }],
      },
      error: null,
      loading: false,
    };
    render(<TerminateStaffPage />);
    expect(screen.getByText(/employment has already ended/i)).toBeInTheDocument();
    expect(screen.queryByRole("form", { name: /terminate employment/i })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Terminate" })).toBeDisabled();
    expect(screen.getByRole("link", { name: /see their record/i })).toHaveAttribute("href", "/staff/s1");
  });

  it("says so plainly when the address belongs to nobody on the register", () => {
    paramsRef.current = { id: "gone" };
    render(<TerminateStaffPage />);
    expect(screen.getByText(/can’t find that person/i)).toBeInTheDocument();
  });
});
