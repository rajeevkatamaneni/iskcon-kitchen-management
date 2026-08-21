import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { LeaveView, MealCrewView, WeekScheduleView } from "@/lib/api";

const { authRef, queueRef, rosterRef, impactRef, reloadMock, decideMock, recordMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queueRef: { current: { data: [] as LeaveView[], error: null, loading: false } },
  rosterRef: { current: { data: null as WeekScheduleView | null, error: null, loading: false } },
  impactRef: { current: { data: [] as MealCrewView[], error: null, loading: false } },
  reloadMock: vi.fn(),
  decideMock: vi.fn(),
  recordMock: vi.fn(),
}));

// The screen reads its own address bar now (item 22), so the stub has to answer both halves of
// next/navigation: what the URL says, and what a click asks the router to do with it.
const { pushMock, replaceMock, paramsRef } = vi.hoisted(() => ({
  pushMock: vi.fn(),
  replaceMock: vi.fn(),
  paramsRef: { current: new URLSearchParams() },
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock, replace: replaceMock }),
  useSearchParams: () => paramsRef.current,
  useParams: () => ({ id: "id-1" }),
}));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// Three queries: the leave itself, the roster the "record it for them" form names, and what
// approving a request would cost the kitchen. Told apart by what the callback asks for, which is
// the only thing left once the hook is a stub.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const asks = fn.toString();
    const ref = asks.includes("leaveQueue") ? queueRef : asks.includes("leaveImpact") ? impactRef : rosterRef;
    return { ...ref.current, reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, decideLeave: decideMock, recordLeave: recordMock },
  };
});

import LeavePage from "@/app/leave/page";
import RecordLeavePage from "@/app/leave/record/page";

function leave(overrides: Partial<LeaveView> = {}): LeaveView {
  return {
    id: "l1",
    staffProfileId: "p1",
    staffName: "Head Cook A",
    jobTitleLabel: "Head Cook",
    leaveType: "SICK",
    leaveTypeLabel: "Sick leave",
    fromDate: "2026-09-03",
    toDate: "2026-09-04",
    halfDay: false,
    reason: "Fever",
    status: "PENDING",
    requestedByName: "Head Cook A",
    requestedAt: "2026-09-01T04:00:00Z",
    decidedByName: null,
    decidedAt: null,
    decisionNote: null,
    ...overrides,
  };
}

const ROSTER: WeekScheduleView = {
  weekStart: "2026-08-31",
  staff: [
    { staffProfileId: "p2", userId: null, fullName: "Janitor with no app", jobTitleLabel: "Janitor", days: [] },
  ],
  counts: [],
};

describe("leave queue", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    queueRef.current = { data: [leave()], error: null, loading: false };
    rosterRef.current = { data: ROSTER, error: null, loading: false };
    reloadMock.mockReset();
    decideMock.mockReset().mockResolvedValue(undefined);
    recordMock.mockReset().mockResolvedValue({ id: "new-leave" });
    paramsRef.current = new URLSearchParams();
    pushMock.mockReset();
    replaceMock.mockReset();
    impactRef.current = { data: [], error: null, loading: false };
  });

  it("lists what is waiting, with who asked and for what", () => {
    render(<LeavePage />);
    expect(screen.getByRole("heading", { name: "Leave" })).toBeInTheDocument();
    expect(screen.getByText(/Sick leave · 2026-09-03 to 2026-09-04/)).toBeInTheDocument();
    expect(screen.getByText("Asked for by Head Cook A")).toBeInTheDocument();
    expect(screen.getByText("Fever")).toBeInTheDocument();
    // Twice on purpose: the filter this screen opens on, and the badge on the row itself.
    expect(screen.getAllByText("Waiting")).toHaveLength(2);
  });

  it("tells the approver what a day off costs, and still lets them give it", async () => {
    // Item 24. The approver is the person best placed to know this leaves lunch short and the worst
    // placed to work it out. Told, and never stopped — the button is still there and still works.
    impactRef.current = {
      data: [
        {
          planDate: "2026-09-03",
          mealKind: "Lunch",
          readyBy: "12:00:00",
          crewRequired: 8,
          staffIn: 3,
          volunteers: 1,
          rostered: 4,
          shortOfCrew: true,
        },
      ],
      error: null,
      loading: false,
    };
    render(<LeavePage />);

    // The date is written by shortDate, so its order follows the reader's locale; what is asserted
    // is the meal, the day and the two numbers, not the arrangement of the month and the day.
    const line = screen.getByText(/^Approving this leaves Lunch on .* at 4 of 8\.$/);
    expect(line.textContent).toContain("Sep");

    const approve = screen.getByRole("button", { name: "Approve" });
    expect(approve).not.toBeDisabled();
    fireEvent.click(approve);
    await waitFor(() => expect(decideMock).toHaveBeenCalledWith("l1", "approve", null, "test-token"));
  });

  it("says nothing where the day off costs the kitchen nothing", () => {
    render(<LeavePage />);
    expect(screen.queryByText(/Approving this leaves/)).not.toBeInTheDocument();
  });

  it("approves a waiting request", async () => {
    render(<LeavePage />);
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(decideMock).toHaveBeenCalledWith("l1", "approve", null, "test-token"));
  });

  it("declines a waiting request", async () => {
    render(<LeavePage />);
    fireEvent.click(screen.getByRole("button", { name: "Decline" }));
    await waitFor(() => expect(decideMock).toHaveBeenCalledWith("l1", "decline", null, "test-token"));
  });

  // Approved leave is what the grid and the head count read, so taking it back has to be possible
  // from the same screen that granted it.
  it("offers to revoke leave that was approved", async () => {
    queueRef.current = {
      data: [leave({ status: "APPROVED", decidedByName: "Temple Admin" })],
      error: null,
      loading: false,
    };
    paramsRef.current = new URLSearchParams("tab=APPROVED");
    render(<LeavePage />);
    fireEvent.click(screen.getByRole("button", { name: "Revoke" }));
    await waitFor(() => expect(decideMock).toHaveBeenCalledWith("l1", "revoke", null, "test-token"));
  });

  // Item 22: which tab you are on is what you are looking at, so it goes in the address bar and
  // back returns to the tab before it rather than throwing you off the screen.
  it("puts the tab in the URL, and pushes so back returns to the one before", () => {
    render(<LeavePage />);
    fireEvent.click(screen.getByRole("tab", { name: "Approved" }));
    expect(pushMock).toHaveBeenCalledWith("/leave?tab=APPROVED");
  });

  it("opens on the tab a deep link names", () => {
    queueRef.current = {
      data: [leave({ status: "APPROVED", decidedByName: "Temple Admin" })],
      error: null,
      loading: false,
    };
    paramsRef.current = new URLSearchParams("tab=APPROVED");
    render(<LeavePage />);
    expect(screen.getByRole("tab", { name: "Approved" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("button", { name: "Revoke" })).toBeInTheDocument();
  });

  it("sends recording leave to its own screen", () => {
    render(<LeavePage />);
    // Six fields, and the queue's own tabs used to show behind the panel it replaces.
    expect(screen.getByRole("link", { name: /record leave for someone/i })).toHaveAttribute(
      "href",
      "/leave/record"
    );
  });

  it("shows the confirmation recorded leave comes back with", () => {
    paramsRef.current = new URLSearchParams("tab=APPROVED&recorded=Janitor%20with%20no%20app");
    render(<LeavePage />);
    expect(screen.getByText(/Janitor with no app.s leave was recorded and approved\./i)).toBeInTheDocument();
  });

  it("says so plainly when nothing is waiting", () => {
    queueRef.current = { data: [], error: null, loading: false };
    render(<LeavePage />);
    expect(screen.getByText("Nothing waiting")).toBeInTheDocument();
  });

  // The role exists precisely so that a manager can answer leave without being handed salary or PAN.
  it("admits a kitchen manager", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_MANAGER", userId: "me" } };
    render(<LeavePage />);
    expect(screen.getByRole("heading", { name: "Leave" })).toBeInTheDocument();
  });

  it("refuses a cook", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<LeavePage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});

describe("recording leave for somebody with no login", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    rosterRef.current = { data: ROSTER, error: null, loading: false };
    recordMock.mockReset().mockResolvedValue({ id: "new-leave" });
    pushMock.mockReset();
  });

  it("records it already approved, and returns to the queue with the confirmation", async () => {
    render(<RecordLeavePage />);

    const form = screen.getByRole("form", { name: "Record leave" });
    fireEvent.change(form.querySelector('input[name="fromDate"]')!, { target: { value: "2026-09-10" } });
    fireEvent.change(form.querySelector('input[name="toDate"]')!, { target: { value: "2026-09-12" } });
    // The commit button is in the sticky header, outside the form, and reaches it by name.
    fireEvent.click(screen.getByRole("button", { name: "Record it" }));

    await waitFor(() => expect(recordMock).toHaveBeenCalled());
    expect(recordMock.mock.calls[0][0]).toMatchObject({
      staffProfileId: "p2",
      fromDate: "2026-09-10",
      toDate: "2026-09-12",
      halfDay: false,
    });
    expect(pushMock).toHaveBeenCalledWith("/leave?tab=APPROVED&recorded=Janitor%20with%20no%20app");
  });

  it("offers Cancel rather than a back-link", () => {
    render(<RecordLeavePage />);
    expect(screen.getByRole("link", { name: "Cancel" })).toHaveAttribute("href", "/leave");
    expect(screen.queryByText(/←/)).not.toBeInTheDocument();
  });

  it("refuses a cook", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<RecordLeavePage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
