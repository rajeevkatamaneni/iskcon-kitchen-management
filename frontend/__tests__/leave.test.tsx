import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { LeaveView, WeekScheduleView } from "@/lib/api";

const { authRef, queueRef, rosterRef, reloadMock, decideMock, recordMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  queueRef: { current: { data: [] as LeaveView[], error: null, loading: false } },
  rosterRef: { current: { data: null as WeekScheduleView | null, error: null, loading: false } },
  reloadMock: vi.fn(),
  decideMock: vi.fn(),
  recordMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
// Two queries: the leave itself, and the roster the "record it for them" form names. Told apart by
// what the callback asks for, which is the only thing left once the hook is a stub.
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: (fn: (t: string | undefined) => Promise<unknown>) => {
    const ref = fn.toString().includes("leaveQueue") ? queueRef : rosterRef;
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
    render(<LeavePage />);
    fireEvent.click(screen.getByRole("tab", { name: "Approved" }));
    fireEvent.click(screen.getByRole("button", { name: "Revoke" }));
    await waitFor(() => expect(decideMock).toHaveBeenCalledWith("l1", "revoke", null, "test-token"));
  });

  it("records leave for somebody with no login, already approved", async () => {
    render(<LeavePage />);
    fireEvent.click(screen.getByRole("button", { name: /record leave for someone/i }));

    const form = screen.getByRole("form", { name: "Record leave" });
    fireEvent.change(form.querySelector('input[name="fromDate"]')!, { target: { value: "2026-09-10" } });
    fireEvent.change(form.querySelector('input[name="toDate"]')!, { target: { value: "2026-09-12" } });
    fireEvent.submit(form);

    await waitFor(() => expect(recordMock).toHaveBeenCalled());
    expect(recordMock.mock.calls[0][0]).toMatchObject({
      staffProfileId: "p2",
      fromDate: "2026-09-10",
      toDate: "2026-09-12",
      halfDay: false,
    });
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
