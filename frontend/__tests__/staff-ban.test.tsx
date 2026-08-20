import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type {
  ApiError,
  BanCategoryOption,
  BanFinding,
  JobTitleOption,
  StaffPayView,
  StaffProfileView,
  StaffRegisterView,
  UserSummary,
} from "@/lib/api";

/**
 * The two ban surfaces on the staff register (B9).
 *
 * <p>What is worth pinning here is not that the fields render. It is that the option to record a ban
 * is <b>off</b> unless somebody deliberately turns it on, that turning it on demands both halves of
 * the reason, and that findings at a hire are presented as something to read and act on rather than
 * as a refusal — the raising temple named, the signals that matched spelled out, and both answers
 * offered as answers. Every one of those is a decision that a well-meaning tidy-up could quietly
 * reverse.
 */

const {
  authRef,
  registerRef,
  titlesRef,
  categoriesRef,
  payRef,
  devoteesRef,
  reloadMock,
  hireMock,
  endMock,
  abandonMock,
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
  categoriesRef: { current: { data: [] as BanCategoryOption[], error: null, loading: false } },
  payRef: { current: { data: null as StaffPayView | null, error: null, loading: false } },
  devoteesRef: { current: { data: [] as UserSummary[], error: null, loading: false } },
  reloadMock: vi.fn(),
  hireMock: vi.fn(),
  endMock: vi.fn(),
  abandonMock: vi.fn(),
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
        : source.includes("banCategories")
          ? categoriesRef
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
      endEmployment: endMock,
      abandonHireCheck: abandonMock,
    },
  };
});

import StaffPage from "@/app/staff/page";

const CATEGORIES: BanCategoryOption[] = [
  { value: "THEFT", label: "Theft or misappropriation" },
  { value: "HARASSMENT", label: "Harassment or abuse" },
];

const TITLES: JobTitleOption[] = [
  { value: "COOK", label: "Cook", group: "KITCHEN", suggestedAccess: "KITCHEN_STAFF" },
];

function member(o: Partial<StaffProfileView> = {}): StaffProfileView {
  return {
    id: "s1",
    userId: "u1",
    fullName: "Gopal Das",
    phone: "+919876500001",
    email: "gopal@example.com",
    jobTitle: "COOK",
    jobTitleOther: null,
    jobTitleLabel: "Cook",
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

const FINDING: BanFinding = {
  banId: "b1",
  raisingTempleName: "ISKCON South Bengaluru",
  category: "THEFT",
  categoryLabel: "Theft or misappropriation",
  bannedName: "Ramesh Kumar",
  account: "Took ₹18,000 from the donation box over three weeks.",
  raisedOn: "2026-03-04",
  signals: ["PAN", "NAME"],
  signalLabels: ["PAN", "Name"],
  exact: true,
};

function openTerminationPanel() {
  render(<StaffPage />);
  fireEvent.click(screen.getByRole("button", { name: "Terminate" }));
  return screen.getByRole("form", { name: /terminate employment/i });
}

describe("recording a ban when somebody is dismissed", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    registerRef.current = { data: { current: [member()], former: [] }, error: null, loading: false };
    titlesRef.current = { data: TITLES, error: null, loading: false };
    categoriesRef.current = { data: CATEGORIES, error: null, loading: false };
    payRef.current = { data: null, error: null, loading: false };
    devoteesRef.current = { data: [], error: null, loading: false };
    reloadMock.mockReset();
    hireMock.mockReset().mockResolvedValue({ id: "new" });
    endMock.mockReset().mockResolvedValue(undefined);
    abandonMock.mockReset().mockResolvedValue(undefined);
  });

  it("is off unless somebody deliberately turns it on", () => {
    const form = openTerminationPanel();

    const tick = form.querySelector('input[name="raiseBan"]') as HTMLInputElement;
    expect(tick).toBeInTheDocument();
    expect(tick).not.toBeChecked();

    // Nothing about the reason is even on the screen until it is asked for. Most dismissals warn
    // nobody, and the screen should read that way.
    expect(form.querySelector('select[name="banCategory"]')).toBeNull();
    expect(form.querySelector('textarea[name="banAccount"]')).toBeNull();
  });

  it("demands both a category and an account of what happened once it is on", () => {
    const form = openTerminationPanel();
    fireEvent.click(form.querySelector('input[name="raiseBan"]') as HTMLInputElement);

    const category = form.querySelector('select[name="banCategory"]') as HTMLSelectElement;
    const account = form.querySelector('textarea[name="banAccount"]') as HTMLTextAreaElement;
    expect(category).toBeRequired();
    expect(account).toBeRequired();

    // The category is comparable across temples; the words are what the other temple rings about.
    expect(within(form).getByText("Theft or misappropriation")).toBeInTheDocument();
  });

  it("warns, before anything is written, that the person is never shown it", () => {
    const form = openTerminationPanel();
    fireEvent.click(form.querySelector('input[name="raiseBan"]') as HTMLInputElement);

    expect(within(form).getByText(/are not told about it and cannot answer it/i)).toBeInTheDocument();
    expect(within(form).getByText(/ten years/i)).toBeInTheDocument();
    expect(within(form).getByText(/take it back at any time/i)).toBeInTheDocument();
  });

  it("sends the record with the dismissal, as one act", async () => {
    const form = openTerminationPanel();
    fireEvent.change(form.querySelector('input[name="lastWorkingDay"]') as HTMLInputElement, {
      target: { value: "2026-08-15" },
    });
    fireEvent.click(form.querySelector('input[name="raiseBan"]') as HTMLInputElement);
    fireEvent.change(form.querySelector('select[name="banCategory"]') as HTMLSelectElement, {
      target: { value: "HARASSMENT" },
    });
    fireEvent.change(form.querySelector('textarea[name="banAccount"]') as HTMLTextAreaElement, {
      target: { value: "Two written warnings, then a third incident." },
    });
    fireEvent.submit(form);

    await waitFor(() => expect(endMock).toHaveBeenCalled());
    expect(endMock.mock.calls[0][1]).toMatchObject({
      status: "RESIGNED",
      ban: { category: "HARASSMENT", account: "Two written warnings, then a third incident." },
    });
  });

  it("sends no record at all when the option was left alone", async () => {
    const form = openTerminationPanel();
    fireEvent.change(form.querySelector('input[name="lastWorkingDay"]') as HTMLInputElement, {
      target: { value: "2026-08-15" },
    });
    fireEvent.submit(form);

    await waitFor(() => expect(endMock).toHaveBeenCalled());
    expect(endMock.mock.calls[0][1].ban).toBeNull();
  });
});

describe("what the check finds at a hire", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    registerRef.current = { data: { current: [], former: [] }, error: null, loading: false };
    titlesRef.current = { data: TITLES, error: null, loading: false };
    categoriesRef.current = { data: CATEGORIES, error: null, loading: false };
    payRef.current = { data: null, error: null, loading: false };
    devoteesRef.current = { data: [], error: null, loading: false };
    reloadMock.mockReset();
    hireMock.mockReset();
    endMock.mockReset().mockResolvedValue(undefined);
    abandonMock.mockReset().mockResolvedValue(undefined);
  });

  async function hireAndGetFindings() {
    hireMock.mockResolvedValueOnce({ checkId: "check-1", findings: [FINDING] });
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: /hire someone/i }));
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    fireEvent.change(form.querySelector('input[name="fullName"]') as HTMLInputElement, {
      target: { value: "Ramesh Kumar" },
    });
    fireEvent.change(form.querySelector('input[name="dateOfJoining"]') as HTMLInputElement, {
      target: { value: "2026-08-01" },
    });
    fireEvent.submit(form);
    return screen.findByRole("region", { name: /another temple has recorded something/i });
  }

  it("names the temple, quotes what they wrote, and says which details matched", async () => {
    const panel = await hireAndGetFindings();

    expect(within(panel).getByText("ISKCON South Bengaluru")).toBeInTheDocument();
    expect(within(panel).getByText(/Took ₹18,000 from the donation box/)).toBeInTheDocument();
    expect(within(panel).getByText(/Theft or misappropriation/)).toBeInTheDocument();
    expect(within(panel).getByText(/employed there as Ramesh Kumar/)).toBeInTheDocument();
    expect(within(panel).getByText(/Recorded 2026-03-04/)).toBeInTheDocument();
    expect(within(panel).getByText(/Matched exactly on: PAN, Name/)).toBeInTheDocument();
  });

  it("offers both answers, and does not pretend the hire was refused", async () => {
    const panel = await hireAndGetFindings();

    expect(within(panel).getByRole("button", { name: /hire them anyway/i })).toBeInTheDocument();
    expect(within(panel).getByRole("button", { name: /don’t hire them/i })).toBeInTheDocument();
    expect(within(panel).getByText(/Whichever you choose is recorded/i)).toBeInTheDocument();
    // Not an error: nothing has gone wrong, and rendering it as a failure would read as a block.
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("hires anyway when the admin says so, carrying the check they answered", async () => {
    const panel = await hireAndGetFindings();
    hireMock.mockResolvedValueOnce({ id: "new" });

    fireEvent.click(within(panel).getByRole("button", { name: /hire them anyway/i }));

    await waitFor(() => expect(hireMock).toHaveBeenCalledTimes(2));
    expect(hireMock.mock.calls[1][0]).toMatchObject({
      fullName: "Ramesh Kumar",
      acknowledgedBanCheckId: "check-1",
    });
  });

  it("records the decision to walk away, which would otherwise leave no trace", async () => {
    const panel = await hireAndGetFindings();

    fireEvent.click(within(panel).getByRole("button", { name: /don’t hire them/i }));

    await waitFor(() => expect(abandonMock).toHaveBeenCalledWith("check-1", "test-token"));
    expect(hireMock).toHaveBeenCalledTimes(1);
  });

  it("says nothing at all when the check found nothing", async () => {
    hireMock.mockResolvedValueOnce({ id: "new" });
    render(<StaffPage />);
    fireEvent.click(screen.getByRole("button", { name: /hire someone/i }));
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    fireEvent.change(form.querySelector('input[name="fullName"]') as HTMLInputElement, {
      target: { value: "Priya Sharma" },
    });
    fireEvent.change(form.querySelector('input[name="dateOfJoining"]') as HTMLInputElement, {
      target: { value: "2026-08-01" },
    });
    fireEvent.submit(form);

    await waitFor(() => expect(reloadMock).toHaveBeenCalled());
    expect(
      screen.queryByRole("region", { name: /another temple has recorded something/i })
    ).not.toBeInTheDocument();
  });
});
