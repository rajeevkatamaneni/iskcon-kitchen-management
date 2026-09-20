import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import {
  ApiError,
  type BanCategoryOption,
  type EmploymentBanView,
  type StaffConductNoteView,
  type StaffPayView,
  type StaffRecordView,
} from "@/lib/api";
import { CATEGORIES, ban, former, member, pay, payment, record } from "./staff-fixtures";

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
  recordRef,
  payRef,
  bansRef,
  categoriesRef,
  conductRef,
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
  recordRef: {
    current: { data: null as StaffRecordView | null, error: null as ApiError | null, loading: false },
  },
  payRef: { current: { data: null as StaffPayView | null, error: null as ApiError | null, loading: false } },
  bansRef: { current: { data: [] as EmploymentBanView[], error: null, loading: false } },
  categoriesRef: { current: { data: [] as BanCategoryOption[], error: null, loading: false } },
  // The conduct-notes panel reads through the same hook (E6-S16). Given its own branch below so it
  // never falls through to whichever ref happens to be last in the chain.
  conductRef: { current: { data: [] as StaffConductNoteView[], error: null, loading: false } },
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
    const ref = source.includes("staffConductNotes")
      ? conductRef
      : source.includes("staffMember")
      ? recordRef
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
    recordRef.current = {
      data: record(formerWithEverything().profile, { banned: formerWithEverything().banned }),
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
    conductRef.current = { data: [], error: null, loading: false };
    revealMock.mockReset().mockResolvedValue({ pan: "ABCDE1234F" });
    amendMock.mockReset().mockResolvedValue(undefined);
    retractMock.mockReset().mockResolvedValue(undefined);
  });

  it("is headed by the person, not by the task (T-428)", () => {
    render(<StaffRecordPage />);
    // Rajeev, 2026-09-20: "their name is prominent". It used to be "Staff record", with the person
    // on a quiet line under it, because FocusScreen makes the task the heading. This is not one.
    expect(screen.getByRole("heading", { level: 1, name: "Madhava Das" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Staff record" })).not.toBeInTheDocument();
    expect(screen.getByText(/Kitchen assistant · Main kitchen · left/)).toBeInTheDocument();
    // A back-link rather than a Close, which is what not being a focus screen earns.
    expect(screen.getByRole("link", { name: "← Staff" })).toHaveAttribute("href", "/staff");
    expect(screen.queryByRole("link", { name: "Close" })).not.toBeInTheDocument();
  });

  it("offers no Edit on somebody who has left, because the server refuses the save", () => {
    render(<StaffRecordPage />);
    expect(screen.queryByRole("link", { name: "Edit" })).not.toBeInTheDocument();
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

  it("keeps the PAN behind the eye, because reading one is recorded", async () => {
    render(<StaffRecordPage />);
    expect(screen.getByText("••••••234F")).toBeInTheDocument();
    // Nothing is fetched by opening the record. The whole point of the eye.
    expect(revealMock).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Show the PAN" }));
    await waitFor(() => expect(screen.getByText("ABCDE1234F")).toBeInTheDocument());
    expect(revealMock).toHaveBeenCalledWith("s2", "test-token");

    // Hiding throws the value away, so showing it again is a second read — and a second audit row.
    fireEvent.click(screen.getByRole("button", { name: "Hide the PAN" }));
    expect(screen.getByText("••••••234F")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show the PAN" }));
    await waitFor(() => expect(revealMock).toHaveBeenCalledTimes(2));
  });

  it("shows the record this temple raised, whole", () => {
    bansRef.current = { data: [ban({ personName: "Madhava Das" })], error: null, loading: false };
    render(<StaffRecordPage />);

    expect(screen.getByText("Theft or misappropriation")).toBeInTheDocument();
    expect(screen.getByText(/Took ₹18,000 from the donation box/)).toBeInTheDocument();
    // The temple's own day, written the way every other date in the app is — the raised-at is an
    // Instant, and 09:00Z is the afternoon of the 1st in India, not the day before.
    expect(screen.getByText(/Recorded 1 Jul 2026/)).toBeInTheDocument();
    expect(screen.getByText(/Shown to hiring temples until 1 Jul 2036/)).toBeInTheDocument();
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
    recordRef.current = { data: record(), error: null, loading: false };
    render(<StaffRecordPage />);
    expect(screen.getByRole("link", { name: "Edit" })).toHaveAttribute("href", "/staff/s1/edit");
    // Exact: "Previous employment" is a region on this screen too since T-428.
    const employment = screen.getByRole("region", { name: "Employment" });
    expect(within(employment).getByText("Head Cook")).toBeInTheDocument();
    expect(within(employment).queryByText(/how it ended/i)).not.toBeInTheDocument();
  });

  it("says so plainly when the address belongs to nobody at this temple", () => {
    paramsRef.current = { id: "gone" };
    recordRef.current = {
      data: null,
      error: new ApiError({
        code: "KMS-400030",
        message: "We couldn’t find that.",
        action: "Go back and try again.",
        fieldErrors: [],
      }),
      loading: false,
    };
    render(<StaffRecordPage />);
    expect(screen.getByText(/can’t find that person/i)).toBeInTheDocument();
  });
});
