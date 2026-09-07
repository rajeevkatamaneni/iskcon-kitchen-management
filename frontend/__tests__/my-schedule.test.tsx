import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import {
  ApiError,
  type ScheduleDay,
  type ScheduleExceptionView,
  type ScheduleLeaveDay,
  type StaffProfileView,
  type StaffProfileDetailView,
} from "@/lib/api";

// my-schedule issues exactly one useAuthedQuery call: the signed-in person's own schedule.
const { authRef, returnsRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: {
    current: [] as Array<{ data: unknown; error: unknown; loading: boolean }>,
    i: 0,
  },
  reloadMock: vi.fn(),
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

import MySchedulePage from "@/app/my-schedule/page";

/**
 * The page's own content, without the sidebar.
 *
 * <p>Every assertion goes through this. The sidebar is rendered by the page and carries rows called
 * "Today", "My schedule" and — for an admin — "Staff schedule", so an unscoped query would pass or
 * fail on the menu rather than on the screen. The sidebar is a `<nav>`; the content is the `<main>`.
 */
function content() {
  return within(screen.getByRole("main"));
}

/** Mon–Fri mornings, weekends off — the pattern most of the kitchen actually works. */
const TEMPLATE: ScheduleDay[] = [
  { dayOfWeek: 1, working: true, startTime: "06:00:00", endTime: "14:00:00" },
  { dayOfWeek: 2, working: true, startTime: "06:00:00", endTime: "14:00:00" },
  { dayOfWeek: 3, working: false, startTime: null, endTime: null },
  { dayOfWeek: 4, working: true, startTime: "06:00:00", endTime: "14:00:00" },
  { dayOfWeek: 5, working: true, startTime: "06:00:00", endTime: "14:00:00" },
  { dayOfWeek: 6, working: false, startTime: null, endTime: null },
  { dayOfWeek: 7, working: false, startTime: null, endTime: null },
];

/**
 * Three overrides, each about a different rule: hours changed on a day inside the horizon, the
 * outbound half of a swap (the one way the table can still say "not working"), and one already gone.
 */
const EXCEPTIONS: ScheduleExceptionView[] = [
  {
    id: "ex-1",
    exceptionDate: "2026-09-10",
    working: true,
    startTime: "12:00:00",
    endTime: "20:00:00",
    note: "Covering the evening",
  },
  {
    id: "ex-2",
    exceptionDate: "2026-09-14",
    working: false,
    startTime: null,
    endTime: null,
    note: "Swapped to Saturday",
  },
  {
    id: "ex-3",
    exceptionDate: "2026-09-05",
    working: true,
    startTime: "06:00:00",
    endTime: "10:00:00",
    note: "Festival morning",
  },
];

/**
 * The whole employment record, as the endpoint really sends it — address, date of birth and the
 * last four of a PAN included. The fixture carries them so that the assertion about *not* drawing
 * them is testing something.
 */
const PROFILE: StaffProfileView = {
  id: "p1",
  userId: "me",
  fullName: "Gopal Das",
  phone: null,
  email: null,
  jobTitle: "COOK",
  jobTitleOther: null,
  jobTitleLabel: "Cook",
  employmentType: "FULL_TIME",
  dateOfJoining: "2024-04-01",
  dateOfBirth: "1990-01-01",
  address: "12 Temple Road",
  emergencyContactName: null,
  emergencyContactRelationship: null,
  emergencyContactPhone: null,
  panLast4: "1234",
  systemAccess: "KITCHEN_STAFF",
  employmentStatus: "ACTIVE",
  lastWorkingDay: null,
  endReason: null,
  notes: null,
  createdAt: "2024-04-01T00:00:00Z",
};

/**
 * Two days of approved leave, resolved by the server onto dates (T-032).
 *
 * <p>Both are days the template has the person working, which is the case the screen was wrong
 * about: a full day replaces the hours, and a half day does not — they are in for part of it. The
 * flag is the server's answer and is never worked out here, so a fixture is the whole of what the
 * screen knows about leave.
 */
const LEAVE: ScheduleLeaveDay[] = [
  {
    date: "2026-09-11",
    leaveId: "lv-half",
    leaveType: "SICK",
    leaveLabel: "Sick leave",
    halfDayLeave: true,
  },
  {
    date: "2026-09-17",
    leaveId: "lv-full",
    leaveType: "TIME_OFF",
    leaveLabel: "Time off",
    halfDayLeave: false,
  },
];

/** The window `/schedule/me` resolves: four weeks from the temple's today, 8 Sep to 5 Oct. */
const LEAVE_FROM = "2026-09-08";
const LEAVE_TO = "2026-10-05";

const SCHEDULE: StaffProfileDetailView = {
  profile: PROFILE,
  template: TEMPLATE,
  exceptions: EXCEPTIONS,
  leaveDays: LEAVE,
  leaveFrom: LEAVE_FROM,
  leaveTo: LEAVE_TO,
};

/** The row for one day, so an assertion about hours cannot pass on a different day's hours. */
function row(label: string): HTMLElement {
  const cell = content().getByText(label);
  const li = cell.closest("li");
  if (!li) throw new Error(`No row for ${label}`);
  return li;
}

/** What `/schedule/me` answers for somebody the temple does not employ. */
const NO_RECORD = new ApiError(
  {
    code: "KMS-400030",
    message: "We couldn’t find that.",
    action: "Check the link and try again.",
    fieldErrors: [],
  },
  404
);

describe("my schedule", () => {
  beforeEach(() => {
    // 19:00 UTC on Monday 7 September is 00:30 on Tuesday 8 September in Asia/Kolkata, which is the
    // clock this screen keeps. Pinned so the horizon is the same fourteen days on every machine —
    // and chosen so the temple's day and the device's UTC day deliberately differ, because that
    // difference is the thing being asserted.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date("2026-09-07T19:00:00Z"));

    authRef.current = { status: "signed-in", appUser: { role: "KITCHEN_STAFF", userId: "me" } };
    returnsRef.current = [{ data: SCHEDULE, error: null, loading: false }];
    returnsRef.i = 0;
    reloadMock.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("lists the days ahead the person is rostered for, with their hours", () => {
    render(<MySchedulePage />);
    expect(screen.getByRole("heading", { name: /my schedule/i })).toBeInTheDocument();
    expect(content().getByText("Tuesday, 8 September")).toBeInTheDocument();
    expect(content().getByText("Friday, 11 September")).toBeInTheDocument();
    expect(content().getAllByText("06:00–14:00").length).toBeGreaterThan(0);
  });

  // The clock is the temple's, not the device's: at the pinned instant the browser's UTC day is
  // still the 7th, and a horizon starting there would open on a Monday the kitchen has finished.
  it("starts at the temple's own today, not the device's", () => {
    render(<MySchedulePage />);
    expect(content().getByText("Tuesday, 8 September")).toBeInTheDocument();
    expect(content().queryByText("Monday, 7 September")).not.toBeInTheDocument();
    expect(content().getByText("Today")).toBeInTheDocument();
  });

  it("shows a changed day as changed, with the note the manager left", () => {
    render(<MySchedulePage />);
    expect(content().getByText("Thursday, 10 September")).toBeInTheDocument();
    expect(content().getByText("12:00–20:00")).toBeInTheDocument();
    expect(content().getByText("Covering the evening")).toBeInTheDocument();
    expect(content().getAllByText("Changed").length).toBe(2);
  });

  // The outbound half of a swap. "You are no longer in that Monday" is exactly the change this
  // screen exists to carry, so it is listed even though the person is not working it.
  it("keeps a day an override turned off, and leaves ordinary days off out", () => {
    render(<MySchedulePage />);
    expect(content().getByText("Monday, 14 September")).toBeInTheDocument();
    expect(content().getByText("Swapped to Saturday")).toBeInTheDocument();
    // Wednesday is off by the pattern, so it is not in the list at all.
    expect(content().queryByText("Wednesday, 9 September")).not.toBeInTheDocument();
  });

  it("leaves out an override that has already gone", () => {
    render(<MySchedulePage />);
    expect(content().queryByText("Saturday, 5 September")).not.toBeInTheDocument();
    expect(content().queryByText("Festival morning")).not.toBeInTheDocument();
  });

  it("shows the weekly pattern underneath", () => {
    render(<MySchedulePage />);
    expect(screen.getByRole("heading", { name: /your usual week/i })).toBeInTheDocument();
    expect(content().getByText("Wed")).toBeInTheDocument();
    expect(content().getAllByText("Off").length).toBeGreaterThan(0);
  });

  // The defect T-032 fixes, and the sentence it makes untrue. A cook approved for the 17th read the
  // 17th's hours, on the one screen written for them, while their manager's grid showed the leave.
  it("shows a full day of approved leave as leave, and not as hours", () => {
    render(<MySchedulePage />);
    const thursday = row("Thursday, 17 September");
    expect(within(thursday).getByText("Time off")).toBeInTheDocument();
    expect(within(thursday).queryByText("06:00–14:00")).not.toBeInTheDocument();
    expect(content().queryByText(/approved leave is not shown here/i)).not.toBeInTheDocument();
    expect(content().queryByText(/approved leave isn’t shown here/i)).not.toBeInTheDocument();
  });

  // The case a browser-side reimplementation gets wrong. Half a day off leaves them in for the other
  // half, so blanking the hours would tell them not to come in at all.
  it("keeps the hours on a half day, and marks it as one", () => {
    render(<MySchedulePage />);
    const friday = row("Friday, 11 September");
    expect(within(friday).getByText("06:00–14:00")).toBeInTheDocument();
    expect(within(friday).getByText("Sick leave, half day")).toBeInTheDocument();
  });

  // Absent leave fields mean *not resolved*, never *no leave* — the shape /staff/profiles/{id}
  // sends. Silence about a fortnight and a clear fortnight must not look the same on screen.
  it("says so when it was told nothing about leave", () => {
    returnsRef.current = [
      { data: { profile: PROFILE, template: TEMPLATE, exceptions: EXCEPTIONS }, error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<MySchedulePage />);
    expect(content().getByText(/approved leave isn’t shown here/i)).toBeInTheDocument();
    // And nothing is drawn as leave, because nothing was answered.
    expect(within(row("Thursday, 17 September")).getByText("06:00–14:00")).toBeInTheDocument();
  });

  // Leave is drawn where it was answered for and nowhere else. A window that stops short says so
  // rather than letting the days past it read as clear.
  it("draws no leave past the window it was answered across", () => {
    returnsRef.current = [
      {
        data: { ...SCHEDULE, leaveTo: "2026-09-12" },
        error: null,
        loading: false,
      },
    ];
    returnsRef.i = 0;
    render(<MySchedulePage />);
    // Inside the window: still drawn.
    expect(within(row("Friday, 11 September")).getByText("Sick leave, half day")).toBeInTheDocument();
    // Outside it: hours, and a sentence saying the screen was not told.
    expect(within(row("Thursday, 17 September")).getByText("06:00–14:00")).toBeInTheDocument();
    expect(content().getByText(/only shown up to 12 Sep/i)).toBeInTheDocument();
  });

  // Nothing from the employment record but the roster: the payload carries date of birth, address
  // and the last four of a PAN, and none of it is this screen's question.
  it("draws nothing from the employment record", () => {
    render(<MySchedulePage />);
    expect(content().queryByText(/12 Temple Road/)).not.toBeInTheDocument();
    expect(content().queryByText(/1234/)).not.toBeInTheDocument();
    expect(content().queryByText(/1990/)).not.toBeInTheDocument();
  });

  it("renders for a kitchen manager and a temple admin as well as a cook", () => {
    for (const role of ["TEMPLE_ADMIN", "KITCHEN_MANAGER", "KITCHEN_STAFF"]) {
      authRef.current = { status: "signed-in", appUser: { role, userId: "me" } };
      returnsRef.i = 0;
      const view = render(<MySchedulePage />);
      expect(screen.getByRole("heading", { name: /my schedule/i })).toBeInTheDocument();
      expect(content().getByText("Tuesday, 8 September")).toBeInTheDocument();
      expect(screen.queryByText(/not your page/i)).not.toBeInTheDocument();
      view.unmount();
    }
  });

  // D-10: a volunteer holds VIEW_OWN_SHIFTS and has no staff profile for it to read. Their seva is
  // /my-shifts, and this page is not theirs.
  it("refuses a volunteer", () => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    render(<MySchedulePage />);
    expect(screen.getByText(/not your page/i)).toBeInTheDocument();
  });

  describe("with no staff record", () => {
    beforeEach(() => {
      returnsRef.current = [{ data: null, error: NO_RECORD, loading: false }];
      returnsRef.i = 0;
    });

    // The rule this batch is fixing in four other places: an empty screen never points its reader
    // at a page that will refuse them. /staff-schedule admits admins and managers only.
    it("tells a cook who to ask, and offers no page they'd be refused at", () => {
      render(<MySchedulePage />);
      expect(content().getByText(/you’re not on the staff roster/i)).toBeInTheDocument();
      expect(content().getByText(/your kitchen manager sets who works when/i)).toBeInTheDocument();
      expect(content().queryByRole("link")).not.toBeInTheDocument();
    });

    it("offers the roster to somebody who can actually open it", () => {
      authRef.current = { status: "signed-in", appUser: { role: "TEMPLE_ADMIN", userId: "me" } };
      render(<MySchedulePage />);
      expect(content().getByRole("link", { name: /staff schedule/i })).toHaveAttribute(
        "href",
        "/staff-schedule"
      );
    });

    // A 404 here is not a failure the reader caused, so it is answered in words rather than shown
    // as one with a code to quote.
    it("shows no error code", () => {
      render(<MySchedulePage />);
      expect(content().queryByText(/KMS-400030/)).not.toBeInTheDocument();
    });
  });

  it("says so plainly when nothing is rostered ahead", () => {
    returnsRef.current = [
      {
        data: {
          profile: PROFILE,
          template: TEMPLATE.map((d) => ({ ...d, working: false, startTime: null, endTime: null })),
          exceptions: [],
        },
        error: null,
        loading: false,
      },
    ];
    returnsRef.i = 0;
    render(<MySchedulePage />);
    expect(content().getByText(/you have no days in the next 14/i)).toBeInTheDocument();
    // The pattern stays on screen, and it is what carries who to ask.
    expect(screen.getByRole("heading", { name: /your usual week/i })).toBeInTheDocument();
  });
});
