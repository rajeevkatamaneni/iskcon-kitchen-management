import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { DayCoverage, ResolvedDay, WeekScheduleView } from "@/lib/api";

// staff-schedule issues three useAuthedQuery calls, in this order: the week grid, the coverage for
// the week on screen, and the coverage for the thirty days ahead.
const {
  authRef,
  returnsRef,
  reloadMock,
  setExceptionMock,
  deleteExceptionMock,
  swapMock,
  recordLeaveMock,
  decideLeaveMock,
} = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: { current: [] as Array<{ data: unknown; error: null; loading: boolean }>, i: 0 },
  reloadMock: vi.fn(),
  setExceptionMock: vi.fn(),
  deleteExceptionMock: vi.fn(),
  swapMock: vi.fn(),
  recordLeaveMock: vi.fn(),
  decideLeaveMock: vi.fn(),
}));

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn(), push: vi.fn() }) }));
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ ...authRef.current, getToken: async () => "test-token" }),
}));
vi.mock("@/lib/use-authed-query", () => ({
  useAuthedQuery: () => {
    const list = returnsRef.current;
    const value = list[returnsRef.i % list.length];
    returnsRef.i += 1;
    return { ...value, reload: reloadMock };
  },
}));
vi.mock("@/lib/api", async (orig) => {
  const actual = await orig<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      setStaffException: setExceptionMock,
      deleteStaffException: deleteExceptionMock,
      swapStaffShift: swapMock,
      recordLeave: recordLeaveMock,
      decideLeave: decideLeaveMock,
    },
  };
});

import StaffSchedulePage from "@/app/staff-schedule/page";

/** The fields every resolved day carries, so a fixture states only what it is about. */
const PLAIN = {
  exceptionId: null,
  swapLinkId: null,
  leaveId: null,
  leaveType: null,
  leaveLabel: null,
  halfDayLeave: false,
} satisfies Partial<ResolvedDay>;

const WEEK: WeekScheduleView = {
  weekStart: "2026-08-31",
  staff: [
    {
      staffProfileId: "p1",
      userId: "u1",
      fullName: "Head Cook A",
      jobTitleLabel: "Head Cook",
      days: [
        { ...PLAIN, date: "2026-08-31", dayOfWeek: 1, working: true, startTime: "09:00:00", endTime: "17:00:00", fromException: false },
        // Tuesday is the outbound half of a swap: not working, and linked to the day they took instead.
        { ...PLAIN, date: "2026-09-01", dayOfWeek: 2, working: false, startTime: null, endTime: null, fromException: true, exceptionId: "ex-tue", swapLinkId: "link-1" },
        { ...PLAIN, date: "2026-09-02", dayOfWeek: 3, working: true, startTime: "09:00:00", endTime: "17:00:00", fromException: false },
        // Thursday is approved sick leave — read-only, and the grid refuses to schedule over it.
        { ...PLAIN, date: "2026-09-03", dayOfWeek: 4, working: false, startTime: null, endTime: null, fromException: false, leaveId: "leave-1", leaveType: "SICK", leaveLabel: "Sick leave" },
        { ...PLAIN, date: "2026-09-04", dayOfWeek: 5, working: true, startTime: "09:00:00", endTime: "17:00:00", fromException: false },
        { ...PLAIN, date: "2026-09-05", dayOfWeek: 6, working: false, startTime: null, endTime: null, fromException: false },
        { ...PLAIN, date: "2026-09-06", dayOfWeek: 7, working: false, startTime: null, endTime: null, fromException: false },
      ],
    },
  ],
  counts: [
    { date: "2026-08-31", staffIn: 1, volunteers: 4 },
    { date: "2026-09-01", staffIn: 0, volunteers: 0 },
    { date: "2026-09-02", staffIn: 1, volunteers: 2 },
    { date: "2026-09-03", staffIn: 0, volunteers: 0 },
    { date: "2026-09-04", staffIn: 1, volunteers: 0 },
    { date: "2026-09-05", staffIn: 0, volunteers: 0 },
    { date: "2026-09-06", staffIn: 0, volunteers: 0 },
  ],
};

/** A day with nothing to say, so a fixture states only the part it is about. */
const QUIET: DayCoverage = {
  date: "",
  staffIn: 0,
  volunteers: 0,
  state: "NOTHING_PLANNED",
  shortBy: 0,
  shortAt: null,
  shortAtReadyBy: null,
  shortAtRequired: null,
  shortAtRostered: null,
};

/**
 * The week the grid draws. Monday is two short at dinner, Wednesday has the crew it asked for,
 * Friday has meals nobody has crewed — three different sentences, and the screen has to say which.
 */
const WEEK_COVERAGE: DayCoverage[] = [
  {
    ...QUIET,
    date: "2026-08-31",
    staffIn: 1,
    volunteers: 4,
    state: "SHORT",
    shortBy: 3,
    shortAt: "Dinner",
    shortAtReadyBy: "19:30:00",
    shortAtRequired: 5,
    shortAtRostered: 2,
  },
  { ...QUIET, date: "2026-09-01" },
  { ...QUIET, date: "2026-09-02", staffIn: 1, volunteers: 2, state: "COVERED" },
  { ...QUIET, date: "2026-09-03" },
  { ...QUIET, date: "2026-09-04", staffIn: 1, state: "CREW_NOT_SET" },
  { ...QUIET, date: "2026-09-05" },
  { ...QUIET, date: "2026-09-06" },
];

/** The thirty days ahead: one ordinary gap, one day with nobody at all, one day never crewed. */
const AHEAD: DayCoverage[] = [
  {
    ...QUIET,
    date: "2026-09-10",
    staffIn: 2,
    state: "SHORT",
    shortBy: 2,
    shortAt: "Lunch",
    shortAtReadyBy: "12:00:00",
    shortAtRequired: 6,
    shortAtRostered: 4,
  },
  {
    ...QUIET,
    date: "2026-09-14",
    state: "SHORT",
    shortBy: 5,
    shortAt: "Breakfast",
    shortAtReadyBy: "07:30:00",
    shortAtRequired: 5,
    shortAtRostered: 0,
  },
  { ...QUIET, date: "2026-09-20", state: "CREW_NOT_SET" },
  { ...QUIET, date: "2026-09-21", state: "COVERED" },
];

describe("staff schedule", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
    // Three queries in a fixed order, and the list must be exactly that long: the stub cycles by
    // call index, so a shorter list would hand the grid the coverage fixture on the second render
    // and empty the table mid-interaction.
    returnsRef.current = [
      { data: WEEK, error: null, loading: false },
      { data: WEEK_COVERAGE, error: null, loading: false },
      { data: AHEAD, error: null, loading: false },
    ];
    returnsRef.i = 0;
    reloadMock.mockReset();
    setExceptionMock.mockReset().mockResolvedValue(undefined);
    deleteExceptionMock.mockReset().mockResolvedValue(undefined);
    swapMock.mockReset().mockResolvedValue(undefined);
    recordLeaveMock.mockReset().mockResolvedValue({ id: "new-leave" });
    decideLeaveMock.mockReset().mockResolvedValue(undefined);
  });

  it("renders the weekly grid with hours and an off day from an exception", () => {
    render(<StaffSchedulePage />);
    expect(screen.getByRole("heading", { name: /staff schedule/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Head Cook A" })).toBeInTheDocument();
    expect(screen.getAllByText("09:00–17:00").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Off").length).toBeGreaterThan(0);
  });

  // A11: the register is the only screen salary and PAN appear on, and a kitchen manager runs this
  // grid without holding MANAGE_STAFF. A button offering them a refusal is worse than no button.
  it("no longer offers the staff register from the grid", () => {
    render(<StaffSchedulePage />);
    expect(screen.queryByRole("link", { name: "Staff register" })).not.toBeInTheDocument();
  });

  it("shows the head count at the foot of every column, staff and volunteers apart", () => {
    render(<StaffSchedulePage />);
    expect(screen.getByText("In that day")).toBeInTheDocument();
    expect(screen.getAllByText("1 staff").length).toBe(3);
    expect(screen.getByText("4 volunteers")).toBeInTheDocument();
  });

  // SS1 — the sharpest of the review comments. Both halves of the answer existed and had never been
  // put on the same screen.
  it("says how many people short each column is, and names the meal that is short", () => {
    render(<StaffSchedulePage />);
    expect(screen.getByText("Short of hands")).toBeInTheDocument();
    expect(screen.getByText("3 short")).toBeInTheDocument();
    expect(screen.getByText("Dinner — 2 of 5")).toBeInTheDocument();
    expect(screen.getByText("Covered")).toBeInTheDocument();
  });

  // Null is not zero. A day nobody has crewed must never be drawn as a day that has what it needs.
  it("a day with no crew figure reads as unset, not as covered", () => {
    render(<StaffSchedulePage />);
    expect(screen.getByText("Crew not set")).toBeInTheDocument();
    // Only Wednesday is covered; Friday is not quietly counted with it.
    expect(screen.getAllByText("Covered").length).toBe(1);
  });

  // Colour must never carry the meaning on its own: the shortfall is in words in every cell.
  it("colours by shortfall and still states it in text", () => {
    const { container } = render(<StaffSchedulePage />);
    const short = screen.getByText("3 short");
    expect(short.parentElement?.className).toContain("bg-warning-bg");
    // Nothing is coloured for merely being busy — the quiet days carry no status ground at all.
    expect(container.querySelectorAll(".bg-success-bg").length).toBe(1);
  });

  it("lists the days short of hands in the next thirty, worst case marked as such", () => {
    render(<StaffSchedulePage />);
    expect(screen.getByRole("heading", { name: /short of hands in the next 30 days/i })).toBeInTheDocument();
    expect(screen.getByText("Lunch — 4 of 6")).toBeInTheDocument();
    // Nobody at all is a different fact from being short, and takes the danger tone.
    const nobody = screen.getByText("5 short");
    expect(nobody.className).toContain("bg-danger-bg");
    expect(screen.getByText("Breakfast — 0 of 5")).toBeInTheDocument();
    // A covered day is not listed: the list is what to act on, not a diary.
    expect(screen.queryByText(/21 September/)).not.toBeInTheDocument();
  });

  it("says out loud how many days ahead have no crew figure, rather than reading as an all-clear", () => {
    render(<StaffSchedulePage />);
    expect(screen.getByText(/one other day has meals planned with no crew figure set/i)).toBeInTheDocument();
  });

  it("changing the hours writes an override for that one date", async () => {
    render(<StaffSchedulePage />);
    fireEvent.click(screen.getByRole("button", { name: "Head Cook A, 2026-08-31" }));

    const form = screen.getByRole("form", { name: /change the hours/i });
    fireEvent.change(screen.getByLabelText("From"), { target: { value: "06:00" } });
    fireEvent.change(screen.getByLabelText("To"), { target: { value: "12:00" } });
    fireEvent.submit(form);

    await waitFor(() => expect(setExceptionMock).toHaveBeenCalled());
    expect(setExceptionMock.mock.calls[0][1]).toMatchObject({
      exceptionDate: "2026-08-31",
      working: true,
      startTime: "06:00",
      endTime: "12:00",
    });
  });

  // The rule the whole feature turns on: an absence has one answer, and it is a leave record.
  it("marking someone off records approved leave rather than a schedule override", async () => {
    render(<StaffSchedulePage />);
    fireEvent.click(screen.getByRole("button", { name: "Head Cook A, 2026-08-31" }));
    fireEvent.submit(screen.getByRole("form", { name: /mark them off/i }));

    await waitFor(() => expect(recordLeaveMock).toHaveBeenCalled());
    expect(recordLeaveMock.mock.calls[0][0]).toMatchObject({
      staffProfileId: "p1",
      fromDate: "2026-08-31",
      toDate: "2026-08-31",
    });
    expect(setExceptionMock).not.toHaveBeenCalled();
  });

  it("a swap is sent as one call carrying both days", async () => {
    render(<StaffSchedulePage />);
    fireEvent.click(screen.getByRole("button", { name: "Head Cook A, 2026-08-31" }));

    const form = screen.getByRole("form", { name: /swap this day/i });
    fireEvent.change(screen.getByLabelText(/work this day instead/i), { target: { value: "2026-09-05" } });
    fireEvent.submit(form);

    await waitFor(() => expect(swapMock).toHaveBeenCalled());
    expect(swapMock.mock.calls[0][1]).toMatchObject({ fromDate: "2026-08-31", toDate: "2026-09-05" });
  });

  it("undoing half of a swap says it undoes both", async () => {
    render(<StaffSchedulePage />);
    fireEvent.click(screen.getByRole("button", { name: "Head Cook A, 2026-09-01" }));

    const undo = screen.getByRole("button", { name: /undo the swap \(both days\)/i });
    fireEvent.click(undo);

    await waitFor(() => expect(deleteExceptionMock).toHaveBeenCalledWith("p1", "ex-tue", "test-token"));
  });

  it("approved leave is read-only on the grid, and offers to revoke it instead", async () => {
    render(<StaffSchedulePage />);
    fireEvent.click(screen.getByRole("button", { name: "Head Cook A, 2026-09-03" }));

    expect(screen.queryByRole("form", { name: /change the hours/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("form", { name: /add them on/i })).not.toBeInTheDocument();
    expect(screen.getByText(/revoke the leave first/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: /revoke this leave/i }));
    await waitFor(() => expect(decideLeaveMock).toHaveBeenCalledWith("leave-1", "revoke", null, "test-token"));
  });

  it("refuses a non-admin", () => {
    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    render(<StaffSchedulePage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });
});
