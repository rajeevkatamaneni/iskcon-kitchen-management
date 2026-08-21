import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type {
  ApiError,
  BanCategoryOption,
  EmploymentBanView,
  StaffPayView,
  StaffRegisterView,
} from "@/lib/api";
import { CATEGORIES, ban, former, member, pay, payment } from "./staff-fixtures";

/**
 * A former employee's whole record (E6-S8, B9), read.
 *
 * <p>It exists because a former employee has no editable form and would otherwise have no way into
 * their own record at all. Current staff reach theirs through Update, which is the same record in a
 * form, which is why they get no View of their own (Q6).
 *
 * <p>The record is where a ban is corrected or taken back, and the <b>only</b> place: the list at
 * `/staff/bans` is an audit and is read-only, so that one record can never be changed from two
 * screens. Both halves of that are asserted — here, and in the ban test.
 */

const {
  authRef,
  paramsRef,
  registerRef,
  payRef,
  bansRef,
  categoriesRef,
  revealMock,
  amendMock,
  retractMock,
} = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  paramsRef: { current: { id: "s2" } },
  registerRef: {
    current: { data: null as StaffRegisterView | null, error: null as ApiError | null, loading: false },
  },
  payRef: { current: { data: null as StaffPayView | null, error: null as ApiError | null, loading: false } },
  bansRef: { current: { data: [] as EmploymentBanView[], error: null, loading: false } },
  categoriesRef: { current: { data: [] as BanCategoryOption[], error: null, loading: false } },
  revealMock: vi.fn(),
  amendMock: vi.fn(),
  retractMock: vi.fn(),
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
    const ref = source.includes("staffRegister")
      ? registerRef
      : source.includes("staffPay")
        ? payRef
        : source.includes("templeBans")
          ? bansRef
          : categoriesRef;
    return { ...ref.current, reload: vi.fn() };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, revealStaffPan: revealMock, amendBan: amendMock, retractBan: retractMock },
  };
});

import StaffRecordPage from "@/app/staff/[id]/page";

function formerWithEverything() {
  return former({
    id: "s2",
    fullName: "Madhava Das",
    jobTitleLabel: "Kitchen assistant",
    employmentStatus: "TERMINATED",
    lastWorkingDay: "2026-08-15",
    endReason: "Money missing from the box",
    panLast4: "234F",
    address: "12 MG Road, Bengaluru",
    emergencyContactName: "Sita Devi",
    emergencyContactRelationship: "Sister",
    emergencyContactPhone: "+919876500009",
  });
}

describe("a former employee's record", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    paramsRef.current = { id: "s2" };
    registerRef.current = {
      data: { current: [member()], former: [formerWithEverything()] },
      error: null,
      loading: false,
    };
    payRef.current = {
      data: pay({
        staffId: "s2",
        payments: [payment({ id: "p9", purpose: "SETTLEMENT", purposeLabel: "Settlement", net: 9000, paidOn: "2026-08-20" })],
      }),
      error: null,
      loading: false,
    };
    bansRef.current = { data: [], error: null, loading: false };
    categoriesRef.current = { data: CATEGORIES, error: null, loading: false };
    revealMock.mockReset().mockResolvedValue({ pan: "ABCDE1234F" });
    amendMock.mockReset().mockResolvedValue(undefined);
    retractMock.mockReset().mockResolvedValue(undefined);
  });

  it("is closed, not cancelled — there is nothing here to cancel", () => {
    render(<StaffRecordPage />);
    expect(screen.getByRole("heading", { name: "Staff record" })).toBeInTheDocument();
    expect(screen.getByText(/Madhava Das · Kitchen assistant · left/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Close" })).toHaveAttribute("href", "/staff");
    expect(screen.queryByRole("link", { name: "Cancel" })).not.toBeInTheDocument();
  });

  it("carries the whole record, including how the employment ended and why", () => {
    render(<StaffRecordPage />);
    expect(screen.getByText("Kitchen assistant")).toBeInTheDocument();
    expect(screen.getByText("Full-time")).toBeInTheDocument();
    expect(screen.getByText("Dismissed")).toBeInTheDocument();
    expect(screen.getByText("Money missing from the box")).toBeInTheDocument();
    expect(screen.getByText("12 MG Road, Bengaluru")).toBeInTheDocument();
    expect(screen.getByText(/Sita Devi/)).toBeInTheDocument();
    // The settlement that was paid, which is the one thing that usually happens after the last day.
    expect(screen.getByText(/9,000 on .*20/)).toBeInTheDocument();
  });

  it("keeps the PAN behind a reveal, because reading one is recorded", async () => {
    render(<StaffRecordPage />);
    expect(screen.getByText("••••••234F")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /reveal/i }));
    await waitFor(() => expect(screen.getByText("ABCDE1234F")).toBeInTheDocument());
    expect(revealMock).toHaveBeenCalledWith("s2", "test-token");
  });

  it("shows the record this temple raised, whole", () => {
    bansRef.current = { data: [ban({ personName: "Madhava Das" })], error: null, loading: false };
    render(<StaffRecordPage />);

    expect(screen.getByText("Theft or misappropriation")).toBeInTheDocument();
    expect(screen.getByText(/Took ₹18,000 from the donation box/)).toBeInTheDocument();
    expect(screen.getByText(/Recorded 2026-07-01/)).toBeInTheDocument();
    expect(screen.getByText(/Shown to hiring temples until 2036-07-01/)).toBeInTheDocument();
  });

  it("is where a record is corrected, and where it is taken back", async () => {
    bansRef.current = { data: [ban()], error: null, loading: false };
    render(<StaffRecordPage />);

    fireEvent.click(screen.getByRole("button", { name: /correct what it says/i }));
    const amend = screen.getByRole("form", { name: /correct this record/i });
    fireEvent.change(amend.querySelector('select[name="category"]')!, { target: { value: "HARASSMENT" } });
    fireEvent.change(amend.querySelector('textarea[name="account"]')!, {
      target: { value: "Two written warnings, then a third incident." },
    });
    fireEvent.submit(amend);

    await waitFor(() => expect(amendMock).toHaveBeenCalled());
    expect(amendMock.mock.calls[0][0]).toBe("b1");
    expect(amendMock.mock.calls[0][1]).toMatchObject({
      category: "HARASSMENT",
      account: "Two written warnings, then a third incident.",
    });

    fireEvent.click(screen.getByRole("button", { name: /take it back/i }));
    const retract = screen.getByRole("form", { name: /take this record back/i });
    fireEvent.change(retract.querySelector('input[name="reason"]')!, {
      target: { value: "The money was found." },
    });
    fireEvent.submit(retract);

    await waitFor(() => expect(retractMock).toHaveBeenCalled());
    expect(retractMock.mock.calls[0].slice(0, 2)).toEqual(["b1", "The money was found."]);
  });

  it("offers neither remedy on a record that has already been taken back", () => {
    bansRef.current = {
      data: [ban({ retracted: true, retractedAt: "2026-07-20T09:00:00Z", retractionReason: "We were wrong." })],
      error: null,
      loading: false,
    };
    render(<StaffRecordPage />);

    expect(screen.getByText("Taken back")).toBeInTheDocument();
    expect(screen.getByText(/no longer shown at any hire/i)).toBeInTheDocument();
    expect(screen.getByText("We were wrong.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /correct what it says/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /take it back/i })).not.toBeInTheDocument();
  });

  it("says nothing about bans on somebody nothing was recorded about", () => {
    bansRef.current = { data: [ban({ staffProfileId: "somebody-else" })], error: null, loading: false };
    render(<StaffRecordPage />);
    expect(screen.queryByText(/the record we raised/i)).not.toBeInTheDocument();
  });

  it("opens for a current member of staff too, without the ending", () => {
    paramsRef.current = { id: "s1" };
    render(<StaffRecordPage />);
    const record = screen.getByRole("region", { name: /employment/i });
    expect(within(record).getByText("Head Cook")).toBeInTheDocument();
    expect(within(record).queryByText(/how it ended/i)).not.toBeInTheDocument();
  });

  it("says so plainly when the address belongs to nobody on the register", () => {
    paramsRef.current = { id: "gone" };
    render(<StaffRecordPage />);
    expect(screen.getByText(/can’t find that person/i)).toBeInTheDocument();
  });
});
