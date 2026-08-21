import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import type { ApiError, EmploymentBanView, StaffRegisterView } from "@/lib/api";
import { ban, former, member } from "./staff-fixtures";

/**
 * The staff register (E6-S8), which since 2026-08-21 is a list and nothing else.
 *
 * <p>What is worth pinning here is the shape of the two tables rather than that they render. Both
 * carry the same columns and the same buttons, because a row that reads one way above and another
 * way below has to be learned twice; every action is a link, because each one is a screen with its
 * own address; and a former employee this temple raised a record about is drawn apart from one who
 * simply left, which is the whole of item 2.
 */

const { authRef, registerRef, bansRef, replaceMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  registerRef: {
    current: { data: null as StaffRegisterView | null, error: null as ApiError | null, loading: false },
  },
  bansRef: { current: { data: [] as EmploymentBanView[], error: null, loading: false } },
  replaceMock: vi.fn(),
}));

const searchRef = { current: new URLSearchParams() };

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: replaceMock, push: vi.fn() }),
  useSearchParams: () => searchRef.current,
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const ref = fn.toString().includes("staffRegister") ? registerRef : bansRef;
    return { ...ref.current, reload: vi.fn() };
  },
}));

import StaffPage from "@/app/staff/page";

describe("the staff register", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    registerRef.current = { data: { current: [member()], former: [] }, error: null, loading: false };
    bansRef.current = { data: [], error: null, loading: false };
    searchRef.current = new URLSearchParams();
    replaceMock.mockReset();
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
  });

  it("reads Pay, Update, Terminate — in that order, with Terminate last", () => {
    render(<StaffPage />);
    const current = screen.getByRole("region", { name: /current staff/i });
    const actions = within(current)
      .getAllByRole("link")
      .map((a) => a.textContent);
    expect(actions).toEqual(["Pay", "Update", "Terminate"]);

    expect(within(current).getByRole("link", { name: "Pay" })).toHaveAttribute("href", "/staff/s1/pay");
    expect(within(current).getByRole("link", { name: "Update" })).toHaveAttribute("href", "/staff/s1/edit");
    expect(within(current).getByRole("link", { name: "Terminate" })).toHaveAttribute(
      "href",
      "/staff/s1/terminate"
    );
  });

  it("gives current staff no View, because Update is already the whole record", () => {
    render(<StaffPage />);
    const current = screen.getByRole("region", { name: /current staff/i });
    expect(within(current).queryByRole("link", { name: "View" })).not.toBeInTheDocument();
    // The schedule is a screen of its own; a link per row was noise on the register (A10).
    expect(within(current).queryByRole("link", { name: /schedule/i })).not.toBeInTheDocument();
  });

  it("sends the hire button to its own screen rather than opening a panel here", () => {
    render(<StaffPage />);
    expect(screen.getByRole("link", { name: /hire someone/i })).toHaveAttribute("href", "/staff/new");
    expect(screen.queryByRole("form", { name: /hire a staff member/i })).not.toBeInTheDocument();
  });

  it("gives former staff the same columns and the same buttons as everybody else", () => {
    registerRef.current = {
      data: { current: [member()], former: [former()] },
      error: null,
      loading: false,
    };
    render(<StaffPage />);
    const table = screen.getByRole("region", { name: /former staff/i });

    expect(within(table).getByText("Yamuna Devi Dasi")).toBeInTheDocument();
    // Left is the date and nothing else. The reason moved onto the record, which View opens.
    expect(within(table).getByText("2026-06-30")).toBeInTheDocument();
    expect(within(table).queryByText(/Moved to Mayapur/)).not.toBeInTheDocument();
    expect(within(table).queryByText(/Resigned/)).not.toBeInTheDocument();

    expect(within(table).getByRole("link", { name: "View" })).toHaveAttribute("href", "/staff/s2");
    expect(within(table).getByRole("link", { name: "Pay" })).toHaveAttribute("href", "/staff/s2/pay");
    // A past employment is not edited and cannot be ended twice.
    expect(within(table).queryByRole("link", { name: "Update" })).not.toBeInTheDocument();
    expect(within(table).queryByRole("link", { name: /terminate/i })).not.toBeInTheDocument();
  });

  it("draws a former employee we raised a record about apart from one who simply left", () => {
    registerRef.current = {
      data: {
        current: [],
        former: [former(), former({ id: "s3", fullName: "Madhava Das" }, true)],
      },
      error: null,
      loading: false,
    };
    render(<StaffPage />);
    const table = screen.getByRole("region", { name: /former staff/i });

    const flagged = within(table).getByText("Madhava Das").closest("td")!;
    expect(flagged).toHaveClass("text-danger");
    expect(within(flagged).getByText("Banned")).toBeInTheDocument();

    // And the ordinary leaving is left alone. One glance has to tell the two apart.
    const ordinary = within(table).getByText("Yamuna Devi Dasi").closest("td")!;
    expect(ordinary).not.toHaveClass("text-danger");
    expect(within(ordinary).queryByText("Banned")).not.toBeInTheDocument();
  });

  it("offers the ban list as a quiet line under Former staff, not at the top of the page", () => {
    registerRef.current = {
      data: { current: [member()], former: [former({}, true)] },
      error: null,
      loading: false,
    };
    bansRef.current = { data: [ban(), ban({ id: "b2" })], error: null, loading: false };
    render(<StaffPage />);

    const link = screen.getByRole("link", { name: /records we have raised · 2/i });
    expect(link).toHaveAttribute("href", "/staff/bans");
    // Under the former-staff table, which is the only place it means anything.
    expect(screen.getByRole("region", { name: /former staff/i }).compareDocumentPosition(link))
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  it("says nothing about records when the temple has raised none", () => {
    registerRef.current = { data: { current: [member()], former: [former()] }, error: null, loading: false };
    render(<StaffPage />);
    expect(screen.queryByRole("link", { name: /records we have raised/i })).not.toBeInTheDocument();
  });

  it("hides the former-staff section entirely when nobody has left", () => {
    render(<StaffPage />);
    expect(screen.queryByRole("region", { name: /former staff/i })).not.toBeInTheDocument();
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

  it("carries the confirmation a committed screen sent back, and clears the address", () => {
    searchRef.current = new URLSearchParams("hired=Ramesh+Kumar");
    render(<StaffPage />);
    expect(screen.getByText("Ramesh Kumar is on the staff register.")).toBeInTheDocument();
    // Stripped, so a refresh does not say it again.
    expect(replaceMock).toHaveBeenCalledWith("/staff");
  });

  it("says what happened for an update and for a termination too", () => {
    searchRef.current = new URLSearchParams("terminated=Gopal+Das");
    const { unmount } = render(<StaffPage />);
    expect(screen.getByText("Gopal Das’s employment has ended.")).toBeInTheDocument();
    unmount();
  });

  it("refuses kitchen staff", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<StaffPage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
