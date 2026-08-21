import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import type {
  ApiError,
  BanCategoryOption,
  BanFinding,
  EmploymentBanView,
  JobTitleOption,
  StaffPayView,
  StaffRegisterView,
  UserSummary,
} from "@/lib/api";
import { CATEGORIES, TITLES, ban, member, pay } from "./staff-fixtures";

/**
 * The three ban surfaces (B9): the option on the termination screen, what a check found at a hire,
 * and the audit list of what this temple has ever recorded.
 *
 * <p>What is worth pinning here is not that the fields render. It is that the option to record a ban
 * is <b>off</b> unless somebody deliberately turns it on, that turning it on demands both halves of
 * the reason, that findings at a hire are presented as something to read and act on rather than as a
 * refusal — the raising temple named, the signals that matched spelled out, and both answers offered
 * as answers — and that the list is now read-only, so that nothing can be changed in two places
 * (Q5). Every one of those is a decision that a well-meaning tidy-up could quietly reverse.
 */

const {
  authRef,
  paramsRef,
  registerRef,
  payRef,
  titlesRef,
  categoriesRef,
  bansRef,
  devoteesRef,
  pushMock,
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
  paramsRef: { current: { id: "s1" } },
  registerRef: {
    current: { data: null as StaffRegisterView | null, error: null as ApiError | null, loading: false },
  },
  payRef: { current: { data: null as StaffPayView | null, error: null as ApiError | null, loading: false } },
  titlesRef: { current: { data: [] as JobTitleOption[], error: null, loading: false } },
  categoriesRef: { current: { data: [] as BanCategoryOption[], error: null, loading: false } },
  bansRef: {
    current: { data: [] as EmploymentBanView[], error: null as ApiError | null, loading: false },
  },
  devoteesRef: { current: { data: [] as UserSummary[], error: null, loading: false } },
  pushMock: vi.fn(),
  hireMock: vi.fn(),
  endMock: vi.fn(),
  abandonMock: vi.fn(),
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
        : source.includes("jobTitles")
          ? titlesRef
          : source.includes("banCategories")
            ? categoriesRef
            : source.includes("templeBans")
              ? bansRef
              : devoteesRef;
    return { ...ref.current, reload: vi.fn() };
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

import HireStaffPage from "@/app/staff/new/page";
import StaffBansPage from "@/app/staff/bans/page";
import TerminateStaffPage from "@/app/staff/[id]/terminate/page";

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

function signedInAdmin() {
  authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
  paramsRef.current = { id: "s1" };
  registerRef.current = { data: { current: [member()], former: [] }, error: null, loading: false };
  payRef.current = { data: pay(), error: null, loading: false };
  titlesRef.current = { data: TITLES, error: null, loading: false };
  categoriesRef.current = { data: CATEGORIES, error: null, loading: false };
  bansRef.current = { data: [], error: null, loading: false };
  devoteesRef.current = { data: [], error: null, loading: false };
  pushMock.mockReset();
  hireMock.mockReset().mockResolvedValue({ id: "new" });
  endMock.mockReset().mockResolvedValue(undefined);
  abandonMock.mockReset().mockResolvedValue(undefined);
}

describe("recording a ban when somebody is dismissed", () => {
  beforeEach(signedInAdmin);

  function openTermination() {
    render(<TerminateStaffPage />);
    return screen.getByRole("form", { name: /terminate employment/i });
  }

  it("is off unless somebody deliberately turns it on", () => {
    const form = openTermination();

    const tick = form.querySelector('input[name="raiseBan"]') as HTMLInputElement;
    expect(tick).toBeInTheDocument();
    expect(tick).not.toBeChecked();

    // Nothing about the reason is even on the screen until it is asked for. Most dismissals warn
    // nobody, and the screen should read that way.
    expect(form.querySelector('select[name="banCategory"]')).toBeNull();
    expect(form.querySelector('textarea[name="banAccount"]')).toBeNull();
  });

  it("demands both a category and an account of what happened once it is on", () => {
    const form = openTermination();
    fireEvent.click(form.querySelector('input[name="raiseBan"]') as HTMLInputElement);

    const category = form.querySelector('select[name="banCategory"]') as HTMLSelectElement;
    const account = form.querySelector('textarea[name="banAccount"]') as HTMLTextAreaElement;
    expect(category).toBeRequired();
    expect(account).toBeRequired();

    // The category is comparable across temples; the words are what the other temple rings about.
    expect(within(form).getByText("Theft or misappropriation")).toBeInTheDocument();
  });

  it("warns what it costs the person, and is exempt from the copy cut", () => {
    // Q7: the warning above a ban is one of four texts that are never cut to fit. It is the gravest
    // thing this product lets somebody do, it is read once, and each of these four facts is one
    // they need before they tick the box — so the assertion is on the facts rather than on a
    // sentence, and it fails if a later copy pass shortens any of them away.
    const form = openTermination();
    fireEvent.click(form.querySelector('input[name="raiseBan"]') as HTMLInputElement);

    const warning = within(form).getByRole("status").textContent ?? "";
    expect(warning).toContain("Your temple\u2019s name");
    expect(warning).toContain("any temple that tries to hire this person");
    expect(warning).toContain("ten years");
    expect(warning).toContain("not told about it and cannot answer it");
    expect(warning).toContain("take it back");
  });

  it("sends the record with the dismissal, as one act", async () => {
    const form = openTermination();
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
    const form = openTermination();
    fireEvent.change(form.querySelector('input[name="lastWorkingDay"]') as HTMLInputElement, {
      target: { value: "2026-08-15" },
    });
    fireEvent.submit(form);

    await waitFor(() => expect(endMock).toHaveBeenCalled());
    expect(endMock.mock.calls[0][1].ban).toBeNull();
  });
});

describe("what the check finds at a hire", () => {
  beforeEach(signedInAdmin);

  async function hireAndGetFindings() {
    hireMock.mockResolvedValueOnce({ checkId: "check-1", findings: [FINDING] });
    render(<HireStaffPage />);
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
    expect(pushMock).toHaveBeenCalledWith("/staff");
  });

  it("says nothing at all when the check found nothing", async () => {
    render(<HireStaffPage />);
    const form = screen.getByRole("form", { name: /hire a staff member/i });
    fireEvent.change(form.querySelector('input[name="fullName"]') as HTMLInputElement, {
      target: { value: "Priya Sharma" },
    });
    fireEvent.change(form.querySelector('input[name="dateOfJoining"]') as HTMLInputElement, {
      target: { value: "2026-08-01" },
    });
    fireEvent.submit(form);

    await waitFor(() => expect(pushMock).toHaveBeenCalled());
    expect(
      screen.queryByRole("region", { name: /another temple has recorded something/i })
    ).not.toBeInTheDocument();
  });
});

describe("the list of what this temple has recorded", () => {
  beforeEach(signedInAdmin);

  it("reads the record and links to the person it is about", () => {
    bansRef.current = { data: [ban()], error: null, loading: false };
    render(<StaffBansPage />);

    expect(screen.getByRole("heading", { name: "Records we have raised" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Yamuna Devi Dasi" })).toHaveAttribute("href", "/staff/s2");
    expect(screen.getByText("Theft or misappropriation")).toBeInTheDocument();
    expect(screen.getByText(/Shown to hiring temples until 2036-07-01/)).toBeInTheDocument();
  });

  it("changes nothing, because a record is corrected on the person and nowhere else", () => {
    bansRef.current = { data: [ban()], error: null, loading: false };
    render(<StaffBansPage />);

    expect(screen.queryByRole("button", { name: /correct what it says/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /take it back/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("form")).not.toBeInTheDocument();
  });

  it("is not reached by a back-link, like everything else", () => {
    bansRef.current = { data: [ban()], error: null, loading: false };
    render(<StaffBansPage />);
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });

  it("says the ordinary state of the page out loud when nothing has been raised", () => {
    render(<StaffBansPage />);
    expect(screen.getByText(/your temple has raised none/i)).toBeInTheDocument();
  });

  it("refuses kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<StaffBansPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
