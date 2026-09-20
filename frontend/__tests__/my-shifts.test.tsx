import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import type { MyPastShiftView, MyReleasedShiftView, MyShiftView, MyWaitlistView } from "@/lib/api";

// my-shifts issues four useAuthedQuery calls in order: my shifts, my waitlist, taken off (T-149),
// past shifts (T-429). The mock hands its list out in that order and wraps with `% length`, so an
// array here that is SHORTER than the number of calls does not fail — call four quietly receives
// the shifts list again and the history section renders upcoming shifts. Every array below must
// therefore have four entries. T-429 added the fourth query at the END for the same reason: an
// insertion anywhere above would have re-pointed all three existing sections at the wrong data.
const { authRef, returnsRef, reloadMock } = vi.hoisted(() => ({
  authRef: {
    current: { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } } as {
      status: string;
      appUser: { role: string; userId: string } | null;
    },
  },
  returnsRef: { current: [] as Array<{ data: unknown; error: null; loading: boolean }>, i: 0 },
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

import MyShiftsPage from "@/app/my-shifts/page";

const SHIFT: MyShiftView = {
  signupId: "su1",
  shiftId: "s1",
  title: "Sunday prep",
  shiftDate: "2026-12-06",
  startTime: "08:00",
  endTime: "12:00",
  location: "Main kitchen",
  source: "SIGNUP",
  signedUpAt: "2026-08-01T00:00:00Z",
};

const WAIT: MyWaitlistView = {
  shiftId: "s2",
  title: "Festival cooking",
  shiftDate: "2026-12-07",
  startTime: "06:00",
  endTime: "10:00",
  location: null,
  position: 2,
  joinedAt: "2026-08-01T00:00:00Z",
};

const REMOVED: MyReleasedShiftView = {
  signupId: "su9",
  shiftId: "s9",
  title: "Janmashtami midnight offering",
  shiftDate: "2026-12-10",
  startTime: "20:00",
  endTime: "02:00",
  location: "Temple hall",
  releasedAt: "2026-09-10T06:00:00Z",
  reason: "ROTA_CHANGED",
};

/** A shift she served and was marked as having come to (T-429). */
const SERVED: MyPastShiftView = {
  signupId: "sp1",
  shiftId: "p1",
  title: "Radhastami feast service",
  shiftDate: "2026-09-19",
  startTime: "10:00",
  endTime: "14:30",
  location: "Prasadam hall",
  source: "SIGNUP",
  signedUpAt: "2026-09-01T00:00:00Z",
  attended: true,
  attendanceRecordedAt: "2026-09-19T14:55:00Z",
};

describe("my shifts", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    returnsRef.current = [
      { data: [SHIFT], error: null, loading: false },
      { data: [WAIT], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    reloadMock.mockReset();
  });

  it("lists upcoming shifts with a release action and the waitlist with positions", () => {
    render(<MyShiftsPage />);
    expect(screen.getByRole("heading", { name: /my shifts/i })).toBeInTheDocument();
    expect(screen.getByText("Sunday prep")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /release my spot/i })).toBeInTheDocument();
    expect(screen.getByText("Festival cooking")).toBeInTheDocument();
    expect(screen.getByText(/position 2/i)).toBeInTheDocument();
  });

  it("tells a volunteer their shift runs into the next morning (T-146)", () => {
    // The half a tester notices first. A devotee looking at "20:00–02:00" on their own list of
    // commitments is the person who most needs to be told which day they finish on.
    returnsRef.current = [
      { data: [{ ...SHIFT, title: "Midnight offering", startTime: "20:00", endTime: "02:00" }], error: null, loading: false },
      { data: [{ ...WAIT, startTime: "23:00", endTime: "03:00" }], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<MyShiftsPage />);

    expect(screen.getByText(/20:00–02:00 \(next day\)/)).toBeInTheDocument();
    // And the waitlist half of the same screen, which reads its times from a different view type
    // and had its own copy of the dash.
    expect(screen.getByText(/23:00–03:00 \(next day\)/)).toBeInTheDocument();
  });

  it("shows an empty state with nothing signed up", () => {
    returnsRef.current = [
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<MyShiftsPage />);
    expect(screen.getByText(/no upcoming shifts/i)).toBeInTheDocument();
  });

  it("lists the shifts a volunteer was taken off, with the day, the hours and the reason (T-149)", () => {
    returnsRef.current = [
      { data: [SHIFT], error: null, loading: false },
      { data: [], error: null, loading: false },
      {
        data: [REMOVED, { ...REMOVED, signupId: "su10", shiftId: "s10", title: "Ekadashi prep", startTime: "06:00", endTime: "09:00", location: null, reason: "SHIFT_CANCELLED" }],
        error: null,
        loading: false,
      },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<MyShiftsPage />);

    const section = screen.getByRole("heading", { name: /taken off in the last week/i }).closest("section")!;
    expect(section).toHaveTextContent("Janmashtami midnight offering");
    expect(section).toHaveTextContent("10 Dec 2026 · 20:00–02:00 (next day)");
    expect(section).toHaveTextContent("Temple hall");
    // The volunteer's words, as the removal message prints them after "Reason:", not the
    // coordinator's label.
    expect(section).toHaveTextContent("Reason: the rota changed.");
    expect(section).toHaveTextContent("Ekadashi prep");
    expect(section).toHaveTextContent("Reason: the shift was cancelled.");
    // Nothing to act on: they are no longer on it, so no release button rides along. The one
    // button on the page belongs to the upcoming shift above.
    expect(screen.getAllByRole("button", { name: /release my spot/i })).toHaveLength(1);
  });

  it("says nothing about being taken off when nobody was", () => {
    render(<MyShiftsPage />);
    expect(screen.getByText("Sunday prep")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /taken off/i })).not.toBeInTheDocument();
  });
});

/**
 * What she has already done (T-429). Rajeev, 2026-09-20: "a volunteer cannot see anything she has
 * already done" — before this, /my-shifts showed only what was coming, so a devotee with six past
 * services read an empty page while the coordinator could see all six on the roster.
 */
describe("past shifts", () => {
  beforeEach(() => {
    authRef.current = { status: "signed-in", appUser: { role: "VOLUNTEER", userId: "me" } };
    returnsRef.current = [
      { data: [SHIFT], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    reloadMock.mockReset();
  });

  function withHistory(past: MyPastShiftView[]) {
    returnsRef.current = [
      { data: [SHIFT], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: past, error: null, loading: false },
    ];
    returnsRef.i = 0;
  }

  it("lists what she has already served, with the day, the hours, the place and the mark", () => {
    withHistory([SERVED]);
    render(<MyShiftsPage />);

    const section = screen.getByRole("heading", { name: /^past shifts$/i }).closest("section")!;
    expect(section).toHaveTextContent("Radhastami feast service");
    expect(section).toHaveTextContent("19 Sept 2026 · 10:00–14:30");
    expect(section).toHaveTextContent("Prasadam hall");
    expect(section).toHaveTextContent("Came");
    // Nothing to act on: it has happened. The one button on the page belongs to the upcoming shift.
    expect(screen.getAllByRole("button", { name: /release my spot/i })).toHaveLength(1);
  });

  it("reads an unmarked past shift as unmarked, never as an absence", () => {
    // The rule V107 made the column nullable for, said on the page of the person it would accuse:
    // one shift she was marked absent from, one nobody ever marked at all. The screen must not blur
    // the two. `shift-attendance.test.tsx` holds the coordinator's half of this same rule.
    withHistory([
      { ...SERVED, attended: false, title: "Marked absent" },
      {
        ...SERVED,
        signupId: "sp2",
        shiftId: "p2",
        title: "Nobody ever marked this one",
        attended: null,
        attendanceRecordedAt: null,
      },
    ]);
    render(<MyShiftsPage />);

    const section = screen.getByRole("heading", { name: /^past shifts$/i }).closest("section")!;
    expect(section).toHaveTextContent("Not marked");
    // Exactly one absence on the screen, and it belongs to the shift that was actually marked.
    expect(screen.getAllByText("Did not come")).toHaveLength(1);
  });

  it("does not colour an absence as a warning on the page of the person it is about", () => {
    // The roster paints "Did not come" amber, and rightly: it tells a coordinator a spot went
    // uncovered. Here nothing asks her to act and she cannot change the mark, so the words carry the
    // fact and the colour stays neutral. Pinned, because the obvious "make it consistent with the
    // roster" edit is the one that reintroduces it.
    withHistory([{ ...SERVED, attended: false }]);
    render(<MyShiftsPage />);

    const mark = screen.getByText("Did not come");
    expect(mark.className).not.toMatch(/warning|danger|success/);
  });

  it("says nothing about past shifts when she has never served", () => {
    render(<MyShiftsPage />);
    expect(screen.getByText("Sunday prep")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^past shifts$/i })).not.toBeInTheDocument();
  });

  it("shows one empty box and not two to a volunteer with nothing at all", () => {
    // A brand-new volunteer has no upcoming shifts and no history. She must read one empty state,
    // the one that tells her what to do next — not a second box announcing that she has also never
    // served, which she knows.
    returnsRef.current = [
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
    ];
    returnsRef.i = 0;
    render(<MyShiftsPage />);

    expect(screen.getByText(/no upcoming shifts/i)).toBeInTheDocument();
    expect(screen.getByText(/browse available shifts to offer seva/i)).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^past shifts$/i })).not.toBeInTheDocument();
  });

  it("says so when the list has been cut at the cap", () => {
    // The server returns at most fifty. Fifty rows arriving is the only signal the screen gets that
    // there may be more, so it is the signal it says something about.
    withHistory(
      Array.from({ length: 50 }, (_, n) => ({
        ...SERVED,
        signupId: `sp${n}`,
        shiftId: `p${n}`,
        title: `Service ${n}`,
      })),
    );
    render(<MyShiftsPage />);
    expect(screen.getByText(/showing your 50 most recent shifts/i)).toBeInTheDocument();
  });

  it("stays quiet about the cap when the list is not at it", () => {
    // The negative half, in its own test: two renders in one would leave the first page's DOM in the
    // document and make the absence check search both.
    withHistory([SERVED]);
    render(<MyShiftsPage />);
    expect(screen.queryByText(/showing your 50 most recent/i)).not.toBeInTheDocument();
  });

  it("says a load failed rather than leaving a blank that reads as never having served", () => {
    returnsRef.current = [
      { data: [SHIFT], error: null, loading: false },
      { data: [], error: null, loading: false },
      { data: [], error: null, loading: false },
      {
        data: null,
        error: { code: "KMS-500001", message: "Something went wrong.", action: "Try again." },
        loading: false,
      },
    ] as never;
    returnsRef.i = 0;
    render(<MyShiftsPage />);

    expect(screen.getByRole("heading", { name: /^past shifts$/i })).toBeInTheDocument();
    expect(screen.getByText(/something went wrong/i)).toBeInTheDocument();
  });
});
