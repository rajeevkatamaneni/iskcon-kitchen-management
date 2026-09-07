import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import {
  ApiError,
  type ScheduleDay,
  type ScheduleExceptionView,
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

const SCHEDULE: StaffProfileDetailView = {
  profile: PROFILE,
  template: TEMPLATE,
  exceptions: EXCEPTIONS,
};

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

  it("shows the weekly pattern underneath, and says leave is not in it", () => {
    render(<MySchedulePage />);
    expect(screen.getByRole("heading", { name: /your usual week/i })).toBeInTheDocument();
    expect(content().getByText("Wed")).toBeInTheDocument();
    expect(content().getAllByText("Off").length).toBeGreaterThan(0);
    expect(content().getByText(/approved leave is not shown here/i)).toBeInTheDocument();
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
