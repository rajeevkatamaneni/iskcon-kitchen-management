import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type {
  ApiError,
  JobTitleOption,
  StaffPayView,
  StaffProfileView,
  StaffRegisterView,
  UserSummary,
} from "@/lib/api";

const {
  authRef,
  registerRef,
  titlesRef,
  devoteesRef,
  payRef,
  reloadMock,
  hireMock,
  updateMock,
  endMock,
  revealMock,
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
  // Pay is a fourth query on this page now, fetched only for whichever record a panel is open on.
  payRef: { current: { data: null as StaffPayView | null, error: null, loading: false } },
  reloadMock: vi.fn(),
  hireMock: vi.fn(),
  updateMock: vi.fn(),
  endMock: vi.fn(),
  revealMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// Four queries on this page. They are told apart by what the callback asks for, which is the only
// thing distinguishing them once the hook itself is a stub.
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
      hireStaff: hireMock,
      updateStaffMember: updateMock,
      endEmployment: endMock,
      revealStaffPan: revealMock,
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

const TITLES: JobTitleOption[] = [
  { value: "TEMPLE_ADMINISTRATOR", label: "Temple Administrator", group: "ADMINISTRATION", suggestedAccess: "TEMPLE_ADMIN" },
  { value: "HEAD_COOK", label: "Head Cook", group: "KITCHEN", suggestedAccess: "KITCHEN_STAFF" },
  { value: "COOK", label: "Cook", group: "KITCHEN", suggestedAccess: "KITCHEN_STAFF" },
  { value: "DRIVER", label: "Driver", group: "SUPPORT", suggestedAccess: null },
  { value: "OTHER", label: "Other", group: "OTHER", suggestedAccess: null },
];

describe("the staff register", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    registerRef.current = { data: { current: [member()], former: [] }, error: null, loading: false };
    titlesRef.current = { data: TITLES, error: null, loading: false };
    devoteesRef.current = { data: [], error: null, loading: false };
    payRef.current = { data: null, error: null, loading: false };
    reloadMock.mockReset();
    hireMock.mockReset().mockResolvedValue({ id: "new" });
    updateMock.mockReset().mockResolvedValue(undefined);
    endMock.mockReset().mockResolvedValue(undefined);
    revealMock.mockReset().mockResolvedValue({ pan: "ABCDE1234F" });
  });

  it("lists current staff with their job and access, and gives the room to the actions", () => {
    render(<StaffPage />);
    expect(screen.getByRole("heading", { name: "Staff" })).toBeInTheDocument();
    const current = screen.getByRole("region", { name: /current staff/i });
    expect(within(current).getByText("Gopal Das")).toBeInTheDocument();
    expect(within(current).getByText("Head Cook")).toBeInTheDocument();
    expect(within(current).getByText("Kitchen staff")).toBeInTheDocument();

    // Joined and PAN left the table on 2026-08-20: the date is rarely what anybody scans for, and
    // a PAN has no business sitting in a column beside everybody's. Both are still on the record.
    expect(within(current).queryByText("2026-02-01")).not.toBeInTheDocument();
    expect(within(current).queryByRole("columnheader", { name: /joined/i })).not.toBeInTheDocument();
    expect(within(current).queryByRole("columnheader", { name: /pan/i })).not.toBeInTheDocument();

    // The room went to the actions, which are now buttons rather than underlined words. Pay is the
    // odd one: it looks like its neighbours but is a link, because paying somebody is a page.
    for (const name of ["Update", "Terminate"]) {
      expect(within(current).getByRole("button", { name })).toBeInTheDocument();
    }
    expect(within(current).getByRole("link", { name: "Pay" })).toHaveAttribute(
      "href",
      "/staff/s1/pay"
    );
  });

  it("keeps former staff in their own section, with how and when they left", () => {
    registerRef.current = {
      data: {
        current: [member()],
        former: [
          member({
            id: "s2",
            fullName: "Yamuna Devi Dasi",
            employmentStatus: "RESIGNED",
            lastWorkingDay: "2026-06-30",
            endReason: "Moved to Mayapur",
          }),
        ],
      },
      error: null,
      loading: false,
    };
    render(<StaffPage />);
    const former = screen.getByRole("region", { name: /former staff/i });
    expect(within(former).getByText("Yamuna Devi Dasi")).toBeInTheDocument();
    expect(within(former).getByText(/Resigned — Moved to Mayapur/)).toBeInTheDocument();
    // A past record is read-only; the API refuses an edit and the screen should not offer one.
    expect(within(former).queryByRole("button", { name: "Update" })).not.toBeInTheDocument();
    expect(within(former).queryByRole("button", { name: /terminate/i })).not.toBeInTheDocument();
    // Pay is still offered: a final settlement is usually paid after the last working day. It goes
    // to that person's own pay page, the same as a current row's does.
    expect(within(former).getByRole("link", { name: "Pay" })).toHaveAttribute(
      "href",
      "/staff/s2/pay"
    );
  });

  it("names the row's actions the way an administrator would, and offers no route to the roster", () => {
    render(<StaffPage />);
    const current = screen.getByRole("region", { name: /current staff/i });
    expect(within(current).getByRole("button", { name: "Update" })).toBeInTheDocument();
    expect(within(current).getByRole("button", { name: "Terminate" })).toBeInTheDocument();
    // The schedule is a screen of its own; a link per row was noise on the register (A10).
    expect(within(current).queryByRole("link", { name: /schedule/i })).not.toBeInTheDocument();
  });

  it("hides the former-staff section entirely when nobody has left", () => {
    render(<StaffPage />);
    expect(screen.queryByRole("region", { name: /former staff/i })).not.toBeInTheDocument();
  });

  it("suggests the access a job title usually needs, and lets it be overridden", () => {
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: /hire someone/i }));
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    const title = form.querySelector('select[name="jobTitle"]') as HTMLSelectElement;
    const access = form.querySelector('select[name="systemAccess"]') as HTMLSelectElement;

    fireEvent.change(title, { target: { value: "TEMPLE_ADMINISTRATOR" } });
    expect(access).toHaveValue("TEMPLE_ADMIN");

    // A driver needs no login, and choosing one should stop offering an account.
    fireEvent.change(title, { target: { value: "DRIVER" } });
    expect(access).toHaveValue("");

    // Once the admin says otherwise, the title stops overruling them.
    fireEvent.change(access, { target: { value: "KITCHEN_STAFF" } });
    fireEvent.change(title, { target: { value: "COOK" } });
    expect(access).toHaveValue("KITCHEN_STAFF");
  });

  it("asks for the temple's own words when the title is Other", () => {
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: /hire someone/i }));
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    expect(form.querySelector('input[name="jobTitleOther"]')).toBeNull();

    fireEvent.change(form.querySelector('select[name="jobTitle"]')!, { target: { value: "OTHER" } });
    expect(screen.getByRole("form", { name: /hire a staff member/i })
      .querySelector('input[name="jobTitleOther"]')).toBeTruthy();
  });

  it("hires, sending no PAN when the box was left empty", async () => {
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: /hire someone/i }));
    const form = screen.getByRole("form", { name: /hire a staff member/i });

    fireEvent.change(form.querySelector('input[name="fullName"]')!, { target: { value: "Ramesh Kumar" } });
    fireEvent.change(form.querySelector('input[name="dateOfJoining"]')!, { target: { value: "2026-03-01" } });
    fireEvent.submit(form);

    await waitFor(() => expect(hireMock).toHaveBeenCalled());
    const [input] = hireMock.mock.calls[0];
    expect(input.fullName).toBe("Ramesh Kumar");
    expect(input.dateOfJoining).toBe("2026-03-01");
    // Absent, not "" — an empty string clears a stored PAN and this is a fresh hire either way.
    expect(input.pan).toBeUndefined();
    expect(reloadMock).toHaveBeenCalled();
  });

  it("offers to promote a devotee who already registered here", () => {
    devoteesRef.current = {
      data: [
        {
          id: "u9",
          fullName: "Nitai Das",
          email: "nitai@example.com",
          phone: "+919000000003",
          role: "VOLUNTEER",
          status: "ACTIVE",
          createdAt: "2026-01-01T00:00:00Z",
        },
      ],
      error: null,
      loading: false,
    };
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: /hire someone/i }));
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    const picker = form.querySelector('select[name="existingUserId"]') as HTMLSelectElement;
    expect(picker).toBeTruthy();
    expect(within(picker).getByText(/Nitai Das/)).toBeInTheDocument();
  });

  it("edits a record without ever sending which account it belongs to", async () => {
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: "Update" }));
    const form = screen.getByRole("form", { name: /edit a staff member/i });
    expect(form.querySelector('input[name="fullName"]')).toHaveValue("Gopal Das");

    fireEvent.change(form.querySelector('select[name="jobTitle"]')!, { target: { value: "COOK" } });
    fireEvent.submit(form);

    await waitFor(() => expect(updateMock).toHaveBeenCalled());
    const [id, input] = updateMock.mock.calls[0];
    expect(id).toBe("s1");
    expect(input.jobTitle).toBe("COOK");
    expect("existingUserId" in input).toBe(false);
  });

  it("defaults to taking the sign-in away for a dismissal, but not a resignation", () => {
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: "Terminate" }));
    const form = screen.getByRole("form", { name: /terminate employment/i });
    const revoke = () => form.querySelector('input[name="revokeSignIn"]') as HTMLInputElement;

    expect(revoke().checked).toBe(false);
    fireEvent.change(form.querySelector('select[name="status"]')!, { target: { value: "TERMINATED" } });
    expect(revoke().checked).toBe(true);
  });

  it("ends employment with the reason and the last working day", async () => {
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: "Terminate" }));
    const form = screen.getByRole("form", { name: /terminate employment/i });

    fireEvent.change(form.querySelector('input[name="lastWorkingDay"]')!, { target: { value: "2026-06-30" } });
    fireEvent.change(form.querySelector('input[name="reason"]')!, { target: { value: "Moved to Mayapur" } });
    fireEvent.submit(form);

    await waitFor(() => expect(endMock).toHaveBeenCalled());
    expect(endMock.mock.calls[0][1]).toMatchObject({
      status: "RESIGNED",
      lastWorkingDay: "2026-06-30",
      reason: "Moved to Mayapur",
      revokeSignIn: false,
    });
  });

  it("reads a PAN from the person's own panel, and only when asked", async () => {
    // The masked value used to sit in a column beside every row. Reading one is an audited act, and
    // it now takes opening that person's record — the same guarantee, asked for deliberately.
    registerRef.current = {
      data: { current: [member({ panLast4: "234F" })], former: [] },
      error: null,
      loading: false,
    };
    render(<StaffPage />);
    expect(screen.queryByText("••••••234F")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Update" }));
    expect(screen.getByText("••••••234F")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /reveal/i }));
    await waitFor(() => expect(screen.getByText("ABCDE1234F")).toBeInTheDocument());
    expect(revealMock).toHaveBeenCalledWith("s1", "test-token");
  });

  it("flags a record whose job was never recorded, so somebody fixes it", () => {
    registerRef.current = {
      data: { current: [member({ jobTitle: "UNRECORDED", jobTitleLabel: "Not recorded" })], former: [] },
      error: null,
      loading: false,
    };
    render(<StaffPage />);
    expect(screen.getByText(/job not recorded/i)).toBeInTheDocument();
  });

  it("refuses kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<StaffPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
